package org.http4s
package netty

import cats.effect._
import cats.effect.std.Dispatcher
import cats.implicits._
import com.comcast.ip4s.SocketAddress
import com.typesafe.netty.http._
import fs2.interop.reactivestreams._
import fs2.{Chunk, Stream, io => _}
import org.typelevel.vault.Vault
import io.netty.buffer.{ByteBuf, ByteBufUtil, Unpooled}
import io.netty.channel.{Channel, ChannelFuture}
import io.netty.handler.codec.http._
import io.netty.handler.ssl.SslHandler
import io.netty.util.ReferenceCountUtil
import org.http4s.headers.{Connection => ConnHeader, `Content-Length`, `Transfer-Encoding`}
import org.http4s.{HttpVersion => HV}
import org.typelevel.ci.CIString

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLEngine
import scala.collection.mutable.ListBuffer

/** Helpers for converting http4s request/response objects to and from the netty model
  *
  * Adapted from NettyModelConversion.scala in
  * https://github.com/playframework/playframework/blob/master/framework/src/play-netty-server
  */
private[netty] class NettyModelConversion[F[_]](disp: Dispatcher[F])(implicit F: Async[F]) {

  protected[this] val logger = org.log4s.getLogger

  private val notAllowedWithBody: Set[Method] = Set(Method.HEAD, Method.GET)

  def toNettyRequest(request: Request[F]): HttpRequest = {
    logger.trace(s"Converting request $request")
    val version = HttpVersion.valueOf(request.httpVersion.toString)
    val method = HttpMethod.valueOf(request.method.name)
    val uri = request.uri.withoutFragment.copy(authority = None).renderString

    val req =
      if (notAllowedWithBody.contains(request.method))
        new DefaultFullHttpRequest(version, method, uri)
      else {
        val streamedReq = new DefaultStreamedHttpRequest(
          version,
          method,
          uri,
          StreamUnicastPublisher(
            request.body.chunks
              .evalMap[F, HttpContent](buf => F.delay(chunkToNetty(buf))),
            disp)
        )
        transferEncoding(request.headers, false, streamedReq)
        streamedReq
      }

    request.headers.foreach(appendSomeToNetty(_, req.headers()))
    req.headers().add("Host", request.uri.authority.map(_.renderString))
    req
  }

  def fromNettyResponse(response: HttpResponse): F[(Response[F], (Channel) => F[Unit])] = {
    logger.trace(s"converting response: $response")
    val (body, drain) = convertHttpBody(response)
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
    val buffer = List.newBuilder[Header.Raw]
    headers.forEach { e =>
      buffer += Header.Raw(CIString(e.getKey), e.getValue)
    }
    Headers(buffer.result())
  }

  /** Turn a netty http request into an http4s request
    *
    * @param channel
    *   the netty channel
    * @param request
    *   the netty http request impl
    * @return
    *   Http4s request
    */
  def fromNettyRequest(channel: Channel, request: HttpRequest): Resource[F, Request[F]] = {
    val attributeMap = requestAttributes(
      Option(channel.pipeline().get(classOf[SslHandler])).map(_.engine()),
      channel)

    if (request.decoderResult().isFailure)
      Resource.eval(
        F.raiseError(ParseFailure("Malformed request", "Netty codec parsing unsuccessful")))
    else {
      val (requestBody, cleanup) = convertHttpBody(request)
      val uri: ParseResult[Uri] = Uri.fromString(request.uri())
      val headerBuf = new ListBuffer[Header.Raw]
      val headersIterator = request.headers().iteratorAsString()
      var mapEntry: java.util.Map.Entry[String, String] = null
      while (headersIterator.hasNext) {
        mapEntry = headersIterator.next()
        headerBuf += Header.Raw(CIString(mapEntry.getKey), mapEntry.getValue)
      }

      val method: ParseResult[Method] =
        Method.fromString(request.method().name())
      val version: ParseResult[HV] = HV.fromString(request.protocolVersion().text())

      (for {
        v <- version
        u <- uri
        m <- method
      } yield Request[F](
        m,
        u,
        v,
        Headers(headerBuf.toList),
        requestBody,
        attributeMap
      )) match {
        case Right(http4sRequest) => Resource(F.pure((http4sRequest, cleanup(channel))))
        case Left(err) => Resource.eval(F.raiseError(err))
      }
    }
  }

  protected def requestAttributes(optionalSslEngine: Option[SSLEngine], channel: Channel): Vault =
    (channel.localAddress(), channel.remoteAddress()) match {
      case (local: InetSocketAddress, remote: InetSocketAddress) =>
        Vault.empty
          .insert(
            Request.Keys.ConnectionInfo,
            Request.Connection(
              local = SocketAddress.fromInetSocketAddress(local),
              remote = SocketAddress.fromInetSocketAddress(remote),
              secure = optionalSslEngine.isDefined
            )
          )
      case _ => Vault.empty
    }

  /** Create the source for the http message body
    */
  private[this] def convertHttpBody(request: HttpMessage): (Entity[F], Channel => F[Unit]) =
    request match {
      case full: FullHttpMessage =>
        val content = full.content()
        val buffers = content.nioBuffers()
        if (buffers.isEmpty)
          (Entity.empty, _ => F.unit)
        else {
          val content = full.content()
          val arr = bytebufToArray(content)
          (
            Entity.Strict(Chunk.array(arr)),
            _ => F.unit
          ) // No cleanup action needed
        }
      case streamed: StreamedHttpMessage =>
        val length =
          Option(streamed.headers().get(HttpHeaderNames.CONTENT_LENGTH))
            .flatMap(header => `Content-Length`.parse(header).toOption)
            .map(_.length)
        val isDrained = new AtomicBoolean(false)
        val stream =
          streamed
            .toStreamBuffered(1)
            .flatMap(c => Stream.chunk(Chunk.array(bytebufToArray(c.content()))))
            .onFinalize(F.delay {
              isDrained.compareAndSet(false, true);
              ()
            })
        (Entity.Default(stream, length), drainBody(_, stream, isDrained))
      case _ => (Entity.empty, _ => F.unit)
    }

  /** Return an action that will drain the channel stream in the case that it wasn't drained.
    */
  private[this] def drainBody(c: Channel, f: Stream[F, Byte], isDrained: AtomicBoolean): F[Unit] =
    F.delay {
      if (isDrained.compareAndSet(false, true))
        if (c.isOpen) {
          logger.info("Response body not drained to completion. Draining and closing connection")
          c.close().addListener { (_: ChannelFuture) =>
            // Drain the stream regardless. Some bytebufs often
            // Remain in the buffers. Draining them solves this issue
            disp.unsafeRunAndForget(f.compile.drain)
          }
          ()
        } else
          // Drain anyway, don't close the channel
          disp.unsafeRunAndForget(f.compile.drain)
    }

  /** Append all headers that _aren't_ `Transfer-Encoding` or `Content-Length`
    */
  private[this] def appendSomeToNetty(header: Header.Raw, nettyHeaders: HttpHeaders): Unit = {
    if (header.name != `Transfer-Encoding`.name && header.name != `Content-Length`.name)
      nettyHeaders.add(header.name.toString, header.value)
    ()
  }

  /** Create a Netty response from the result */
  def toNettyResponse(
      httpRequest: Request[F],
      httpResponse: Response[F],
      dateString: String
  ): DefaultHttpResponse = {
    // Http version is 1.0. We can assume it's most likely not.
    var minorIs0 = false
    val httpVersion: HttpVersion =
      if (httpRequest.httpVersion == HV.`HTTP/1.1`)
        HttpVersion.HTTP_1_1
      else if (httpRequest.httpVersion == HV.`HTTP/1.0`) {
        minorIs0 = true
        HttpVersion.HTTP_1_0
      } else
        HttpVersion.valueOf(httpRequest.httpVersion.toString)

    toNonWSResponse(httpRequest, httpResponse, httpVersion, dateString, minorIs0)
  }

  /** Translate an Http4s response to a Netty response.
    *
    * @param httpRequest
    *   The incoming http4s request
    * @param httpResponse
    *   The incoming http4s response
    * @param httpVersion
    *   The netty http version.
    * @param dateString
    *   The calculated date header. May not be used if set explicitly (infrequent)
    * @param minorVersionIs0
    *   Is the http version 1.0. Passed down to not calculate multiple times
    * @return
    */
  protected def toNonWSResponse(
      httpRequest: Request[F],
      httpResponse: Response[F],
      httpVersion: HttpVersion,
      dateString: String,
      minorVersionIs0: Boolean
  ): DefaultHttpResponse = {
    val response =
      if (httpResponse.status.isEntityAllowed && httpRequest.method != Method.HEAD)
        canHaveBodyResponse(httpResponse, httpVersion, minorVersionIs0)
      else {
        val r = new DefaultFullHttpResponse(
          httpVersion,
          HttpResponseStatus.valueOf(httpResponse.status.code)
        )
        httpResponse.headers.foreach(appendSomeToNetty(_, r.headers()))
        // Edge case: HEAD
        // Note: Depending on the status of the response, this may be removed further
        // Down the netty pipeline by the HttpResponseEncoder
        if (httpRequest.method == Method.HEAD) {
          val transferEncoding = httpResponse.headers.get[`Transfer-Encoding`]
          val contentLength = httpResponse.contentLength
          (transferEncoding, contentLength) match {
            case (Some(enc), _) if enc.hasChunked && !minorVersionIs0 =>
              r.headers().add(HttpHeaderNames.TRANSFER_ENCODING, enc.toString)
            case (_, Some(len)) =>
              r.headers().add(HttpHeaderNames.CONTENT_LENGTH, len)
            case _ => // no-op
          }
        }
        r
      }
    // Add the cached date if not present
    if (!response.headers().contains(HttpHeaderNames.DATE))
      response.headers().add(HttpHeaderNames.DATE, dateString)

    httpRequest.headers.get[ConnHeader] match {
      case Some(conn) =>
        response.headers().add(HttpHeaderNames.CONNECTION, ConnHeader.headerInstance.value(conn))
      case None =>
        if (minorVersionIs0) // Close by default for Http 1.0
          response.headers().add(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
    }

    response
  }

  /** Translate an http4s request to an http request that is allowed a body based on the response
    * status.
    */
  private[this] def canHaveBodyResponse(
      httpResponse: Response[F],
      httpVersion: HttpVersion,
      minorIs0: Boolean
  ): DefaultHttpResponse = {
    val response =
      new DefaultStreamedHttpResponse(
        httpVersion,
        HttpResponseStatus.valueOf(httpResponse.status.code),
        StreamUnicastPublisher(
          httpResponse.body.chunks
            .evalMap[F, HttpContent](buf => F.delay(chunkToNetty(buf))),
          disp)
      )
    transferEncoding(httpResponse.headers, minorIs0, response)
    response
  }

  private def transferEncoding(
      headers: Headers,
      minorIs0: Boolean,
      response: StreamedHttpMessage): Unit = {
    headers.foreach(appendSomeToNetty(_, response.headers()))
    val transferEncoding = headers.get[`Transfer-Encoding`]
    headers.get[`Content-Length`] match {
      case Some(clenHeader) if transferEncoding.forall(!_.hasChunked) || minorIs0 =>
        // HTTP 1.1: we have a length and no chunked encoding
        // HTTP 1.0: we have a length

        // Ignore transfer-encoding if it's not chunked
        response.headers().add(HttpHeaderNames.CONTENT_LENGTH, clenHeader.length)

      case _ =>
        if (!minorIs0)
          transferEncoding match {
            case Some(tr) =>
              tr.values.map { v =>
                // Necessary due to the way netty does transfer encoding checks.
                if (v != TransferCoding.chunked)
                  response.headers().add(HttpHeaderNames.TRANSFER_ENCODING, v.coding)
              }
              response
                .headers()
                .add(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
            case None =>
              // Netty reactive streams transfers bodies as chunked transfer encoding anyway.
              response
                .headers()
                .add(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
          }
      // Http 1.0 without a content length means yolo mode. No guarantees on what may happen
      // As the downstream codec takes control from here. There is one more option:
      // Buffering the contents of a stream in an effect and serving them as one static chunk.
      // However, this just to support http 1.0 doesn't seem like the right thing to do,
      // Especially considering it would make it hyper easy to crash http4s-netty apps
      // By just spamming http 1.0 Requests, forcing in-memory buffering and OOM.
    }
    ()
  }

  /** Convert a Chunk to a Netty ByteBuf. */
  protected def chunkToNetty(bytes: Chunk[Byte]): HttpContent =
    if (bytes.isEmpty)
      NettyModelConversion.CachedEmpty
    else
      bytes match {
        case Chunk.ArraySlice(values, offset, length) =>
          new DefaultHttpContent(Unpooled.wrappedBuffer(values, offset, length))
        case c: Chunk.ByteBuffer =>
          new DefaultHttpContent(Unpooled.wrappedBuffer(c.buf))
        case _ =>
          new DefaultHttpContent(Unpooled.wrappedBuffer(bytes.toArray))
      }

  protected def bytebufToArray(buf: ByteBuf): Array[Byte] = {
    val array = ByteBufUtil.getBytes(buf)
    ReferenceCountUtil.release(buf)
    array
  }

}

object NettyModelConversion {
  private[NettyModelConversion] val CachedEmpty: DefaultHttpContent =
    new DefaultHttpContent(Unpooled.EMPTY_BUFFER)
}
