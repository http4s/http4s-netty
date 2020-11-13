package org.http4s.netty
package server

import java.util.concurrent.atomic.AtomicInteger

import cats.effect.ConcurrentEffect
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.{DefaultFullHttpResponse, HttpHeaderNames, HttpRequest, HttpResponse, HttpResponseStatus, HttpUtil}

class HttpServerHandler[F[_]: ConcurrentEffect] extends HttpStreamsHandler[F, HttpRequest, HttpResponse] {
  private val inflight                         = new AtomicInteger(0)
  private var continueExpected: Boolean        = false
  private var sendContinue: Boolean            = false
  private var close                            = false
  private var lastRequest: Option[HttpRequest] = None

  /**
    * Whether the given incoming message has a body.
    */
  override protected def hasBody(request: HttpRequest): Boolean = {
    HttpUtil.getContentLength(request, 0) != 0 || HttpUtil.isTransferEncodingChunked(request)
  }

  /**
    * Create a streamed incoming message with the given stream.
    */
  override protected def createStreamedMessage(request: HttpRequest, stream: fs2.Stream[F, Byte]): HttpRequest =
    new DelegateStreamedHttpRequest[F](request, stream)

  override protected def receivedInMessage(ctx: ChannelHandlerContext): Unit = {
    discard(inflight.incrementAndGet())
  }

  override protected def sentOutMessage(ctx: ChannelHandlerContext): Unit = {
    val inflight = this.inflight.decrementAndGet()
    if (inflight == 1 && continueExpected && sendContinue) {
      lastRequest.foreach { lastRequest =>
        ctx.writeAndFlush(new DefaultFullHttpResponse(lastRequest.protocolVersion, HttpResponseStatus.CONTINUE))
        sendContinue = false
        continueExpected = false
      }
    }
    if (close) discard(ctx.close)
  }

  override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
    // Set to false, since if it was true, and the client is sending data, then the
    // client must no longer be expecting it (due to a timeout, for example).
    continueExpected = false
    sendContinue = false
    msg match {
      case request: HttpRequest =>
        lastRequest = Some(request)
        if (HttpUtil.is100ContinueExpected(request)) continueExpected = true
      case _ =>
    }
    super.channelRead(ctx, msg)
  }

  override protected def unbufferedWrite(ctx: ChannelHandlerContext, out: Outgoing): Unit = {
    /*if (out.message.isInstanceOf[WebSocketHttpResponse]) if (lastRequest.isInstanceOf[FullHttpRequest] || !hasBody(lastRequest)) handleWebSocketResponse(ctx, out)
    else { // If the response has a streamed body, then we can't send the WebSocket response until we've received
      // the body.
      webSocketResponse = out
    }
    else {*/
    val connection = out.message.headers.get(HttpHeaderNames.CONNECTION)
    lastRequest.foreach { lastRequest =>
      if (lastRequest.protocolVersion.isKeepAliveDefault)
        if ("close".equalsIgnoreCase(connection)) close = true
        else if (!"keep-alive".equalsIgnoreCase(connection)) close = true
    }

    if (inflight.get() == 1 && continueExpected) {
      HttpUtil.setKeepAlive(out.message, false)
      close = true
      continueExpected = false
    }
    // According to RFC 7230 a server MUST NOT send a Content-Length or a Transfer-Encoding when the status
    // code is 1xx or 204, also a status code 304 may not have a Content-Length or Transfer-Encoding set.
    if (shouldClose(out)) {
      HttpUtil.setKeepAlive(out.message, false)
      close = true
    }
    super.unbufferedWrite(ctx, out)
  }

  private def shouldClose(out: Outgoing) =
    !HttpUtil.isContentLengthSet(out.message) && !HttpUtil.isTransferEncodingChunked(out.message) && !HttpServerHandler.noBody(
      out.message.status
    )

  /*override protected def consumedInMessage(ctx: ChannelHandlerContext): Unit = {
    if (webSocketResponse != null) {
      handleWebSocketResponse(ctx, webSocketResponse)
      webSocketResponse = null
    }
  }*/

  /*private def handleWebSocketResponse(ctx: ChannelHandlerContext, out: Outgoing): Unit = {
    //val response = out.message.asInstanceOf[WebSocketHttpResponse]
    //val handshaker = currentlyStreamedMessage.map(lastRequest => response.handshakerFactory.newHandshaker(lastRequest))
    val handshaker: Option[WebSocketServerHandshaker] = None
    handshaker match {
      case Some(handshaker) =>
        val pipeline = ctx.pipeline()

        //HandlerPublisher<WebSocketFrame> publisher = new HandlerPublisher<>(ctx.executor(), WebSocketFrame.class);
        //HandlerSubscriber<WebSocketFrame> subscriber = new HandlerSubscriber<>(ctx.executor());
        //pipeline.addAfter(ctx.executor(), ctx.name(), "websocket-subscriber", subscriber);
        //pipeline.addAfter(ctx.executor(), ctx.name(), "websocket-publisher", publisher);

        pipeline.remove(ctx.name());
        // Now remove ourselves from the chain

        // Now do the handshake
        // Wrap the request in an empty request because we don't need the WebSocket handshaker ignoring the body,
        // we already have handled the body.
        lastRequest.foreach(req => handshaker.handshake(ctx.channel(), new EmptyHttpRequest(req)))

      case None =>
        val res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.UPGRADE_REQUIRED);
        res.headers().set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, WebSocketVersion.V13.toHttpHeaderValue);
        HttpUtil.setContentLength(res, 0);
        super.unbufferedWrite(ctx, Outgoing(res, out.promise));
    }
  }*/

  override protected def onPull(ctx: ChannelHandlerContext) = ctx.delay {
    if (continueExpected) if (inflight.get() == 1) {
      lastRequest.foreach { lastRequest =>
        ctx.writeAndFlush(new DefaultFullHttpResponse(lastRequest.protocolVersion, HttpResponseStatus.CONTINUE))
      }
      continueExpected = false
    } else sendContinue = true
  }
}

object HttpServerHandler {
  val noBody = Set(
    HttpResponseStatus.CONTINUE,
    HttpResponseStatus.SWITCHING_PROTOCOLS,
    HttpResponseStatus.PROCESSING,
    HttpResponseStatus.NO_CONTENT,
    HttpResponseStatus.NOT_MODIFIED
  )
}
