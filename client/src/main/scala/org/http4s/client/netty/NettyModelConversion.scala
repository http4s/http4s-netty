package org.http4s.client.netty

import java.util.concurrent.atomic.AtomicBoolean

import cats.implicits._
import com.typesafe.netty.http.{DefaultStreamedHttpRequest, StreamedHttpResponse}
import fs2.Chunk
import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.channel.{Channel, ChannelFuture}
import io.netty.handler.codec.http._
import org.http4s.{Header, Headers, Method, Request, Response, Status, HttpVersion => HV}
import fs2.Stream
import cats.effect.{ConcurrentEffect, IO}
import fs2.interop.reactivestreams._

private[netty] class NettyModelConversion[F[_]](implicit F: ConcurrentEffect[F]) {
  private val notAllowedWithBody: Set[Method] = Set(Method.HEAD, Method.GET)
  private[this] val logger = org.log4s.getLogger

  def toNettyRequest(request: Request[F]): HttpRequest = {
    val version = HttpVersion.valueOf(request.httpVersion.toString)
    val method = HttpMethod.valueOf(request.method.name)
    val uri = request.uri.renderString

    val req =
      if (notAllowedWithBody.contains(request.method))
        new DefaultFullHttpRequest(version, method, uri)
      else
        new DefaultStreamedHttpRequest(
          version,
          method,
          uri,
          request.body.chunks
            .evalMap[F, HttpContent](buf => F.delay(chunkToNetty(buf)))
            .toUnicastPublisher
        )
    req
      .headers()
      .add(request.headers.foldLeft(new DefaultHttpHeaders(): HttpHeaders)((hdrs, header) =>
        hdrs.add(header.name, header.value)))
    req
  }

  def fromNettyResponse(response: HttpResponse): F[(Response[F], (Channel) => F[Unit])] = {
    val (body, drain) = convertResponseBody(response)
    val res = for {
      status <- Status.fromInt(response.status().code())
      version <- HV.fromString(response.protocolVersion().text())
    } yield Response(
      status,
      version,
      toHeaders(response.headers()),
      body
    )

    F.fromEither(res).tupleRight(drain)
  }

  def toHeaders(headers: HttpHeaders) = {
    val buffer = List.newBuilder[Header]
    headers.forEach { e =>
      buffer += Header(e.getKey, e.getValue)
    }
    Headers(buffer.result())
  }

  /** Return an action that will drain the channel stream
    * in the case that it wasn't drained.
    */
  private[this] def drainBody(
      c: Channel,
      f: fs2.Stream[F, Byte],
      isDrained: AtomicBoolean): F[Unit] =
    F.delay {
      if (isDrained.compareAndSet(false, true))
        if (c.isOpen) {
          logger.info("Response body not drained to completion. Draining and closing connection")
          c.close().addListener { (_: ChannelFuture) =>
            //Drain the stream regardless. Some bytebufs often
            //Remain in the buffers. Draining them solves this issue
            F.runAsync(f.compile.drain)(_ => IO.unit).unsafeRunSync()
          }; ()
        } else
          //Drain anyway, don't close the channel
          F.runAsync(f.compile.drain)(_ => IO.unit).unsafeRunSync()
    }

  private[this] def convertResponseBody(
      response: HttpResponse): (fs2.Stream[F, Byte], Channel => F[Unit]) =
    response match {
      case full: FullHttpResponse =>
        val content = full.content()
        val buffers = content.nioBuffers()
        if (buffers.isEmpty)
          (Stream.empty.covary[F], _ => F.unit)
        else {
          val content = full.content()
          val arr = bytebufToArray(content)
          (
            Stream
              .chunk(Chunk.bytes(arr))
              .covary[F],
            _ => F.unit
          ) //No cleanup action needed
        }
      case streamed: StreamedHttpResponse =>
        val isDrained = new AtomicBoolean(false)
        val stream =
          streamed
            .toStream()
            .flatMap(c => Stream.chunk(Chunk.bytes(bytebufToArray(c.content()))))
            .onFinalize(F.delay { isDrained.compareAndSet(false, true); () })
        (stream, drainBody(_, stream, isDrained))
    }

  /** Convert a Chunk to a Netty ByteBuf. */
  private[this] def chunkToNetty(bytes: fs2.Chunk[Byte]): HttpContent =
    if (bytes.isEmpty)
      NettyModelConversion.CachedEmpty
    else
      bytes match {
        case c: Chunk.Bytes =>
          new DefaultHttpContent(Unpooled.wrappedBuffer(c.values, c.offset, c.length))
        case c: Chunk.ByteBuffer =>
          new DefaultHttpContent(Unpooled.wrappedBuffer(c.buf))
        case _ =>
          new DefaultHttpContent(Unpooled.wrappedBuffer(bytes.toArray))
      }

  private[this] def bytebufToArray(buf: ByteBuf): Array[Byte] = {
    val array = new Array[Byte](buf.readableBytes())
    buf.readBytes(array)
    buf.release()
    array
  }
}
object NettyModelConversion {
  private[NettyModelConversion] val CachedEmpty: DefaultHttpContent =
    new DefaultHttpContent(Unpooled.EMPTY_BUFFER)
}
