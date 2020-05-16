package org.http4s.netty

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

import cats.implicits._
import cats.effect.{ConcurrentEffect, IO, Sync}
import com.typesafe.netty.http._
import fs2.interop.reactivestreams._
import fs2.{Chunk, Stream}
import io.chrisdavenport.vault.Vault
import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.channel.{Channel, ChannelFuture}
import io.netty.handler.codec.http._
import io.netty.handler.codec.http.websocketx.{WebSocketFrame => WSFrame, _}
import io.netty.handler.ssl.SslHandler
import javax.net.ssl.SSLEngine
import org.http4s.headers.{`Content-Length`, `Transfer-Encoding`, Connection => ConnHeader}
import org.http4s.server.{SecureSession, ServerRequestKeys}
import org.http4s.websocket.WebSocketFrame._
import org.http4s.websocket.{WebSocketContext, WebSocketFrame}
import org.http4s.{HttpVersion => HV, _}
import org.log4s.getLogger
import org.reactivestreams.{Processor, Subscriber, Subscription}
import scodec.bits.ByteVector

import scala.collection.mutable.ListBuffer

/** Helpers for converting http4s request/response
  * objects to and from the netty model
  *
  * Adapted from NettyModelConversion.scala
  * in
  * https://github.com/playframework/playframework/blob/master/framework/src/play-netty-server
  *
  */
private[netty] final class NettyModelConversion[F[_]](implicit F: ConcurrentEffect[F]) {

  private[this] val logger = getLogger

  /** Turn a netty http request into an http4s request
    *
    * @param channel the netty channel
    * @param request the netty http request impl
    * @return Http4s request
    */
  def fromNettyRequest(
      channel: Channel,
      request: HttpRequest): F[(Request[F], Channel => F[Unit])] = {
    val attributeMap = requestAttributes(
      Option(channel.pipeline().get(classOf[SslHandler])).map(_.engine()),
      channel)

    if (request.decoderResult().isFailure)
      F.raiseError(ParseFailure("Malformed request", "Netty codec parsing unsuccessful"))
    else {
      val (requestBody, cleanup) = convertRequestBody(request)
      val uri: ParseResult[Uri] = Uri.fromString(request.uri())
      val headerBuf = new ListBuffer[Header]
      val headersIterator = request.headers().iteratorAsString()
      var mapEntry: java.util.Map.Entry[String, String] = null
      while (headersIterator.hasNext) {
        mapEntry = headersIterator.next()
        headerBuf += Header(mapEntry.getKey, mapEntry.getValue)
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
        case Right(http4sRequest) => F.pure((http4sRequest, cleanup))
        case Left(err) => F.raiseError(err)
      }
    }
  }

  private def requestAttributes(optionalSslEngine: Option[SSLEngine], channel: Channel): Vault =
    (channel.localAddress(), channel.remoteAddress()) match {
      case (local: InetSocketAddress, remote: InetSocketAddress) =>
        Vault.empty
          .insert(
            Request.Keys.ConnectionInfo,
            Request.Connection(
              local = local,
              remote = remote,
              secure = optionalSslEngine.isDefined
            )
          )
          .insert(
            ServerRequestKeys.SecureSession,
            //Create SSLSession object only for https requests and if current SSL session is not empty. Here, each
            //condition is checked inside a "flatMap" to handle possible "null" values
            optionalSslEngine
              .flatMap(engine => Option(engine.getSession))
              .flatMap { session =>
                (
                  Option(session.getId).map(ByteVector(_).toHex),
                  Option(session.getCipherSuite),
                  Option(session.getCipherSuite).map(CertificateInfo.deduceKeyLength),
                  Option(CertificateInfo.getPeerCertChain(session))
                ).mapN(SecureSession.apply)
              }
          )
      case _ => Vault.empty
    }

  /** Create the source for the request body
    *
    */
  private[this] def convertRequestBody(
      request: HttpRequest): (Stream[F, Byte], Channel => F[Unit]) =
    request match {
      case full: FullHttpRequest =>
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
      case streamed: StreamedHttpRequest =>
        val isDrained = new AtomicBoolean(false)
        val stream =
          streamed
            .toStream()
            .flatMap(c => Stream.chunk(Chunk.bytes(bytebufToArray(c.content()))))
            .onFinalize(F.delay { isDrained.compareAndSet(false, true); () })
        (stream, drainBody(_, stream, isDrained))
    }

  /** Return an action that will drain the channel stream
    * in the case that it wasn't drained.
    */
  private[this] def drainBody(c: Channel, f: Stream[F, Byte], isDrained: AtomicBoolean): F[Unit] =
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

  private[this] def appendAllToNetty(header: Header, nettyHeaders: HttpHeaders) = {
    nettyHeaders.add(header.name.toString(), header.value)
    ()
  }

  /**
    * Append all headers that _aren't_ `Transfer-Encoding` or `Content-Length`
    */
  private[this] def appendSomeToNetty(header: Header, nettyHeaders: HttpHeaders): Unit = {
    if (header.name != `Transfer-Encoding`.name && header.name != `Content-Length`.name)
      nettyHeaders.add(header.name.toString(), header.value)
    ()
  }

  /** Create a Netty response from the result */
  def toNettyResponse(
      httpRequest: Request[F],
      httpResponse: Response[F],
      dateString: String
  ): DefaultHttpResponse = {
    //Http version is 1.0. We can assume it's most likely not.
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

  /** Create a Netty response from the result */
  def toNettyResponseWithWebsocket(
      httpRequest: Request[F],
      httpResponse: Response[F],
      dateString: String,
      maxPayloadLength: Int
  ): F[DefaultHttpResponse] = {
    //Http version is 1.0. We can assume it's most likely not.
    var minorIs0 = false
    val httpVersion: HttpVersion =
      if (httpRequest.httpVersion == HV.`HTTP/1.1`)
        HttpVersion.HTTP_1_1
      else if (httpRequest.httpVersion == HV.`HTTP/1.0`) {
        minorIs0 = true
        HttpVersion.HTTP_1_0
      } else
        HttpVersion.valueOf(httpRequest.httpVersion.toString)

    httpResponse.attributes.lookup(org.http4s.server.websocket.websocketKey[F]) match {
      case Some(wsContext) if !minorIs0 =>
        toWSResponse(
          httpRequest,
          httpResponse,
          httpVersion,
          wsContext,
          dateString,
          maxPayloadLength)
      case _ =>
        F.pure(toNonWSResponse(httpRequest, httpResponse, httpVersion, dateString, minorIs0))
    }
  }

  /** Translate an Http4s response to a Netty response.
    *
    * @param httpRequest The incoming http4s request
    * @param httpResponse The incoming http4s response
    * @param httpVersion The netty http version.
    * @param dateString The calculated date header. May not be used if set explicitly (infrequent)
    * @param minorVersionIs0 Is the http version 1.0. Passed down to not calculate multiple
    *                        times
    * @return
    */
  private[this] def toNonWSResponse(
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
        //Edge case: HEAD
        //Note: Depending on the status of the response, this may be removed further
        //Down the netty pipeline by the HttpResponseEncoder
        if (httpRequest.method == Method.HEAD) {
          val transferEncoding = `Transfer-Encoding`.from(httpResponse.headers)
          val contentLength = `Content-Length`.from(httpResponse.headers)
          (transferEncoding, contentLength) match {
            case (Some(enc), _) if enc.hasChunked && !minorVersionIs0 =>
              r.headers().add(HttpHeaderNames.TRANSFER_ENCODING, enc.toString)
            case (_, Some(len)) =>
              r.headers().add(HttpHeaderNames.CONTENT_LENGTH, len.length)
            case _ => // no-op
          }
        }
        r
      }
    //Add the cached date if not present
    if (!response.headers().contains(HttpHeaderNames.DATE))
      response.headers().add(HttpHeaderNames.DATE, dateString)

    ConnHeader
      .from(httpRequest.headers) match {
      case Some(conn) =>
        response.headers().add(HttpHeaderNames.CONNECTION, conn.value)
      case None =>
        if (minorVersionIs0) //Close by default for Http 1.0
          response.headers().add(HttpHeaderNames.CONNECTION, HttpHeaders.Values.CLOSE)
    }

    response
  }

  /** Translate an http4s request to an http request
    * that is allowed a body based on the response status.
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
        httpResponse.body.chunks
          .evalMap[F, HttpContent](buf => F.delay(chunkToNetty(buf)))
          .toUnicastPublisher()
      )
    httpResponse.headers.foreach(appendSomeToNetty(_, response.headers()))
    val transferEncoding = `Transfer-Encoding`.from(httpResponse.headers)
    `Content-Length`.from(httpResponse.headers) match {
      case Some(clenHeader) if transferEncoding.forall(!_.hasChunked) || minorIs0 =>
        // HTTP 1.1: we have a length and no chunked encoding
        // HTTP 1.0: we have a length

        //Ignore transfer-encoding if it's not chunked
        response.headers().add(HttpHeaderNames.CONTENT_LENGTH, clenHeader.length)

      case _ =>
        if (!minorIs0)
          transferEncoding match {
            case Some(tr) =>
              tr.values.map { v =>
                //Necessary due to the way netty does transfer encoding checks.
                if (v != TransferCoding.chunked)
                  response.headers().add(HttpHeaderNames.TRANSFER_ENCODING, v.coding)
              }
              response
                .headers()
                .add(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
            case None =>
              //Netty reactive streams transfers bodies as chunked transfer encoding anyway.
              response
                .headers()
                .add(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
          }
      //Http 1.0 without a content length means yolo mode. No guarantees on what may happen
      //As the downstream codec takes control from here. There is one more option:
      //Buffering the contents of a stream in an effect and serving them as one static chunk.
      //However, this just to support http 1.0 doesn't seem like the right thing to do,
      //Especially considering it would make it hyper easy to crash http4s-netty apps
      //By just spamming http 1.0 Requests, forcing in-memory buffering and OOM.
    }
    response
  }

  /** Render a websocket response, or if the handshake fails eventually, an error
    * Note: This function is only invoked for http 1.1, as websockets
    * aren't supported for http 1.0.
    *
    * @param httpRequest The incoming request
    * @param httpResponse The outgoing http4s reponse
    * @param httpVersion The calculated netty http version
    * @param wsContext the websocket context
    * @param dateString
    * @return
    */
  private[this] def toWSResponse(
      httpRequest: Request[F],
      httpResponse: Response[F],
      httpVersion: HttpVersion,
      wsContext: WebSocketContext[F],
      dateString: String,
      maxPayloadLength: Int
  ): F[DefaultHttpResponse] =
    if (httpRequest.headers.exists(h =>
        h.name.toString.equalsIgnoreCase("Upgrade") && h.value.equalsIgnoreCase("websocket"))) {
      val wsProtocol = if (httpRequest.isSecure.exists(identity)) "wss" else "ws"
      val wsUrl = s"$wsProtocol://${httpRequest.serverAddr}${httpRequest.pathInfo}"
      val factory = new WebSocketServerHandshakerFactory(wsUrl, "*", true, maxPayloadLength)
      StreamSubscriber[F, WebSocketFrame].flatMap { subscriber =>
        F.delay {
            val processor = new Processor[WSFrame, WSFrame] {
              def onError(t: Throwable): Unit = subscriber.onError(t)

              def onComplete(): Unit = subscriber.onComplete()

              def onNext(t: WSFrame): Unit = subscriber.onNext(nettyWsToHttp4s(t))

              def onSubscribe(s: Subscription): Unit = subscriber.onSubscribe(s)

              def subscribe(s: Subscriber[_ >: WSFrame]): Unit =
                wsContext.webSocket.send.map(wsbitsToNetty).toUnicastPublisher().subscribe(s)
            }

            F.runAsync {
                subscriber
                  .stream(Sync[F].unit)
                  .through(wsContext.webSocket.receive)
                  .compile
                  .drain
              }(_ => IO.unit)
              .unsafeRunSync()
            val resp: DefaultHttpResponse =
              new DefaultWebSocketHttpResponse(
                httpVersion,
                HttpResponseStatus.OK,
                processor,
                factory)
            wsContext.headers.foreach(appendAllToNetty(_, resp.headers()))
            resp
          }
          .handleErrorWith(_ =>
            wsContext.failureResponse.map(
              toNonWSResponse(httpRequest, _, httpVersion, dateString, true)))
      }
    } else
      F.pure(toNonWSResponse(httpRequest, httpResponse, httpVersion, dateString, true))

  private[this] def wsbitsToNetty(w: WebSocketFrame): WSFrame =
    w match {
      case Text(str, last) => new TextWebSocketFrame(last, 0, str)
      case Binary(data, last) =>
        new BinaryWebSocketFrame(last, 0, Unpooled.wrappedBuffer(data.toArray))
      case Ping(data) => new PingWebSocketFrame(Unpooled.wrappedBuffer(data.toArray))
      case Pong(data) => new PongWebSocketFrame(Unpooled.wrappedBuffer(data.toArray))
      case Continuation(data, last) =>
        new ContinuationWebSocketFrame(last, 0, Unpooled.wrappedBuffer(data.toArray))
      case Close(data) => new CloseWebSocketFrame(true, 0, Unpooled.wrappedBuffer(data.toArray))
    }

  private[this] def nettyWsToHttp4s(w: WSFrame): WebSocketFrame =
    w match {
      case c: TextWebSocketFrame => Text(ByteVector(bytebufToArray(c.content())), c.isFinalFragment)
      case c: BinaryWebSocketFrame =>
        Binary(ByteVector(bytebufToArray(c.content())), c.isFinalFragment)
      case c: PingWebSocketFrame => Ping(ByteVector(bytebufToArray(c.content())))
      case c: PongWebSocketFrame => Pong(ByteVector(bytebufToArray(c.content())))
      case c: ContinuationWebSocketFrame =>
        Continuation(ByteVector(bytebufToArray(c.content())), c.isFinalFragment)
      case c: CloseWebSocketFrame => Close(ByteVector(bytebufToArray(c.content())))
    }

  /** Convert a Chunk to a Netty ByteBuf. */
  private[this] def chunkToNetty(bytes: Chunk[Byte]): HttpContent =
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
