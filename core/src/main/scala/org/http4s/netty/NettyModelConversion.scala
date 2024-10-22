/*
 * Copyright 2020 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s
package netty

import cats.effect._
import cats.implicits._
import com.comcast.ip4s.SocketAddress
import com.typesafe.netty.http._
import fs2.Chunk
import fs2.Stream
import fs2.interop.reactivestreams._
import fs2.{io => _}
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.handler.codec.http._
import io.netty.handler.ssl.SslHandler
import io.netty.util.ReferenceCountUtil
import org.http4s.headers.`Content-Length`
import org.http4s.headers.`Transfer-Encoding`
import org.http4s.headers.{Connection => ConnHeader}
import org.http4s.{HttpVersion => HV}
import org.typelevel.ci.CIString
import org.typelevel.vault.Vault

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLEngine

/** Helpers for converting http4s request/response objects to and from the netty model
  *
  * Adapted from NettyModelConversion.scala in
  * https://github.com/playframework/playframework/blob/master/framework/src/play-netty-server
  */
private[netty] class NettyModelConversion[F[_]](implicit F: Async[F]) {
  import NettyModelConversion._

  def toNettyRequest(request: Request[F]): Resource[F, HttpRequest] = {
    logger.trace(s"Converting request $request")
    val version = request.httpVersion match {
      case org.http4s.HttpVersion.`HTTP/1.1` => HttpVersion.HTTP_1_1
      case org.http4s.HttpVersion.`HTTP/1.0` => HttpVersion.HTTP_1_0
      case _ => HttpVersion.valueOf(request.httpVersion.renderString)
    }
    // todo: pattern match to make this faster
    val method = HttpMethod.valueOf(request.method.name)
    val uri = request.uri.toOriginForm.renderString
    // reparse to avoid sending split attack to server
    Uri.fromString(uri) match {
      case Left(value) =>
        Resource.eval(F.raiseError(new IllegalArgumentException("Not a valid URI", value)))
      case Right(_) =>
        val req =
          if (notAllowedWithBody.contains(request.method)) {
            val defaultReq = new DefaultFullHttpRequest(version, method, uri)
            request.headers.foreach(appendSomeToNetty(_, defaultReq.headers()))
            Resource.pure[F, HttpRequest](defaultReq)
          } else {
            StreamUnicastPublisher(
              request.body.chunks
                .evalMap[F, HttpContent](buf => F.delay(chunkToNetty(buf)))).map { publisher =>
              val streamedReq = new DefaultStreamedHttpRequest(version, method, uri, publisher)
              transferEncoding(request.headers, minorIs0 = false, streamedReq)
              streamedReq
            }
          }

        req.map { r =>
          request.uri.authority.foreach(authority =>
            r.headers().add("Host", authority.renderString))
          r
        }
    }
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

  def toNettyHeaders(headers: Headers): HttpHeaders = {
    val defaultHeaders = new DefaultHttpHeaders()
    headers.headers.foreach(h => defaultHeaders.add(h.name.toString, h.value))
    defaultHeaders
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
      val headers = toHeaders(request.headers())

      val method: ParseResult[Method] = request.method() match {
        case HttpMethod.GET => Right(Method.GET)
        case HttpMethod.POST => Right(Method.POST)
        case HttpMethod.OPTIONS => Right(Method.OPTIONS)
        case HttpMethod.HEAD => Right(Method.HEAD)
        case HttpMethod.PUT => Right(Method.PUT)
        case HttpMethod.PATCH => Right(Method.PATCH)
        case HttpMethod.DELETE => Right(Method.DELETE)
        case HttpMethod.TRACE => Right(Method.TRACE)
        case HttpMethod.CONNECT => Right(Method.CONNECT)
        case _ => Method.fromString(request.method().name())
      }
      val requestProtocolVersion = request.protocolVersion()
      val majorVersion = requestProtocolVersion.majorVersion()
      val minorVersion = requestProtocolVersion.minorVersion()
      val version: ParseResult[HV] = majorVersion match {
        case 1 =>
          minorVersion match {
            case 1 => Right(HV.`HTTP/1.1`)
            case 0 => Right(HV.`HTTP/1.0`)
          }
        case 2 =>
          Right(HV.`HTTP/2`)
        case 3 =>
          Right(HV.`HTTP/3`)
        case 0 =>
          Right(HV.`HTTP/0.9`)
        case _ =>
          HV.fromString(requestProtocolVersion.text())
      }

      (for {
        v <- version
        u <- uri
        m <- method
      } yield Request[F](
        m,
        u,
        v,
        headers,
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
  private[this] def convertHttpBody(request: HttpMessage): (Stream[F, Byte], Channel => F[Unit]) =
    request match {
      case full: FullHttpMessage =>
        val content = full.content()
        val buffers = content.nioBuffers()
        if (buffers.isEmpty)
          (Stream.empty.covary[F], _ => F.unit)
        else {
          val content = full.content()
          val arr = NettyModelConversion.bytebufToArray(content)
          (
            Stream
              .chunk(Chunk.array(arr))
              .covary[F],
            _ => F.unit
          ) // No cleanup action needed
        }
      case streamed: StreamedHttpMessage =>
        val isDrained = new AtomicBoolean(false)
        val stream =
          streamed
            .toStreamBuffered(1)
            .flatMap(c =>
              Stream.chunk(Chunk.array(NettyModelConversion.bytebufToArray(c.content()))))
            .onFinalize(F.delay(void(isDrained.compareAndSet(false, true))))
        (stream, drainBody(_, stream, isDrained))
      case _ => (Stream.empty.covary[F], _ => F.unit)
    }

  /** Append all headers that _aren't_ `Transfer-Encoding` or `Content-Length`
    */
  private[this] def appendSomeToNetty(header: Header.Raw, nettyHeaders: HttpHeaders): Unit =
    if (header.name != `Transfer-Encoding`.name && header.name != `Content-Length`.name)
      void(nettyHeaders.add(header.name.toString, header.value))

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
  ): Resource[F, DefaultHttpResponse] = {
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
              ()
            case (_, Some(len)) =>
              r.headers().add(HttpHeaderNames.CONTENT_LENGTH, len)
              ()
            case _ => // no-op
          }
        }
        Resource.pure[F, DefaultHttpResponse](r)
      }

    response.map { response =>
      // Add the cached date if not present
      if (!response.headers().contains(HttpHeaderNames.DATE)) {
        response.headers().add(HttpHeaderNames.DATE, dateString)
        ()
      }

      httpRequest.headers.get[ConnHeader] match {
        case Some(conn) =>
          response.headers().add(HttpHeaderNames.CONNECTION, ConnHeader.headerInstance.value(conn))
        case None =>
          if (minorVersionIs0) { // Close by default for Http 1.0
            response.headers().add(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
            ()
          }
      }
      response
    }
  }

  /** Translate an http4s request to an http request that is allowed a body based on the response
    * status.
    */
  private[this] def canHaveBodyResponse(
      httpResponse: Response[F],
      httpVersion: HttpVersion,
      minorIs0: Boolean
  ): Resource[F, DefaultHttpResponse] =
    StreamUnicastPublisher(
      httpResponse.body.chunks
        .evalMap[F, HttpContent](buf => F.delay(chunkToNetty(buf)))).map { publisher =>
      val response =
        new DefaultStreamedHttpResponse(
          httpVersion,
          HttpResponseStatus.valueOf(httpResponse.status.code),
          publisher
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
          void {
            transferEncoding match {
              case Some(tr) =>
                tr.values.map { v =>
                  // Necessary due to the way netty does transfer encoding checks.
                  if (v != TransferCoding.chunked) {
                    response.headers().add(HttpHeaderNames.TRANSFER_ENCODING, v.coding)
                    ()
                  }
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
    else {
      new DefaultHttpContent(chunkToBytebuf(bytes))
    }
}

object NettyModelConversion {
  private val logger = org.log4s.getLogger
  private[NettyModelConversion] val CachedEmpty: DefaultHttpContent =
    new DefaultHttpContent(Unpooled.EMPTY_BUFFER)

  val notAllowedWithBody: Set[Method] = Set(Method.HEAD, Method.GET)

  /** Return an action that will drain the channel stream in the case that it wasn't drained.
    */
  private[netty] def drainBody[F[_]](c: Channel, f: Stream[F, Byte], isDrained: AtomicBoolean)(
      implicit F: Async[F]): F[Unit] =
    F.delay {
      if (isDrained.compareAndSet(false, true)) {
        if (c.isOpen) {
          logger.info("Response body not drained to completion. Draining and closing connection")
          // Drain the stream regardless. Some bytebufs often
          // Remain in the buffers. Draining them solves this issue
          F.delay(c.close()).liftToF >> f.compile.drain
        } else
          // Drain anyway, don't close the channel
          f.compile.drain
      } else {
        F.unit
      }
    }.flatMap(identity)

  private[netty] def toHeaders(headers: HttpHeaders): Headers = {
    val buffer = List.newBuilder[Header.Raw]
    headers.forEach { e =>
      buffer += Header.Raw(CIString(e.getKey), e.getValue)
    }
    Headers(buffer.result())
  }

  private[netty] def bytebufToArray(buf: ByteBuf, release: Boolean = true): Array[Byte] = {
    val array = ByteBufUtil.getBytes(buf)
    if (release) void(ReferenceCountUtil.release(buf))
    array
  }

  /** Convert a Chunk to a Netty ByteBuf. */
  private[netty] def chunkToBytebuf(bytes: Chunk[Byte]): ByteBuf =
    if (bytes.isEmpty)
      Unpooled.EMPTY_BUFFER
    else
      bytes match {
        case Chunk.ArraySlice(values, offset, length) =>
          Unpooled.wrappedBuffer(values, offset, length)
        case c: Chunk.ByteBuffer =>
          Unpooled.wrappedBuffer(c.buf)
        case _ =>
          Unpooled.wrappedBuffer(bytes.toArray)
      }

}
