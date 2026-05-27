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

package org.http4s.netty.server

import cats.effect.Async
import cats.effect.Ref
import cats.effect.Resource
import cats.effect.kernel.Sync
import cats.syntax.all._
import fs2.Pipe
import fs2.Stream
import fs2.interop.flow.StreamSubscriberWrapper
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.DefaultHttpContent
import io.netty.handler.codec.http.DefaultHttpResponse
import io.netty.handler.codec.http.HttpHeaders
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.LastHttpContent
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory
import io.netty.handler.codec.http.websocketx.{WebSocketFrame => WSFrame}
import org.http4s.Header
import org.http4s.Method
import org.http4s.Request
import org.http4s.Response
import org.http4s.internal.tls._
import org.http4s.netty.NettyModelConversion
import org.http4s.netty.NettyModelConversion.bytebufToArray
import org.http4s.netty.NettyModelConversion.chunkToBytebuf
import org.http4s.netty.NettyModelConversion.resolveHttpVersion
import org.http4s.netty.server.websocket.ZeroCopyBinaryText
import org.http4s.server.SecureSession
import org.http4s.server.ServerRequestKeys
import org.http4s.websocket.WebSocketCombinedPipe
import org.http4s.websocket.WebSocketContext
import org.http4s.websocket.WebSocketFrame
import org.http4s.websocket.WebSocketFrame._
import org.http4s.websocket.WebSocketSeparatePipe
import org.playframework.netty.http.DefaultWebSocketHttpResponse
import org.reactivestreams.FlowAdapters
import org.reactivestreams.Processor
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import org.typelevel.vault.Key
import org.typelevel.vault.Vault
import scodec.bits.ByteVector

import javax.net.ssl.SSLEngine

private[server] final class ServerNettyModelConversion[F[_]](implicit F: Async[F])
    extends NettyModelConversion[F] {
  private val logger = org.log4s.getLogger
  override protected def requestAttributes(
      optionalSslEngine: Option[SSLEngine],
      channel: Channel): Vault =
    super
      .requestAttributes(optionalSslEngine, channel)
      .insert(
        ServerRequestKeys.SecureSession,
        // Create SSLSession object only for https requests and if current SSL session is not empty. Here, each
        // condition is checked inside a "flatMap" to handle possible "null" values
        optionalSslEngine
          .flatMap(engine => Option(engine.getSession))
          .flatMap { session =>
            (
              Option(session.getId).map(ByteVector(_).toHex),
              Option(session.getCipherSuite),
              Option(session.getCipherSuite).map(deduceKeyLength),
              Option(getCertChain(session))
            ).mapN(SecureSession.apply)
          }
      )

  /** Write a simple error response directly to the channel without requiring a parsed Request. Used
    * for error responses when request parsing fails.
    */
  def writeSimpleErrorResponse(ctx: ChannelHandlerContext, httpResponse: Response[F]): F[Unit] = {
    val response = new DefaultFullHttpResponse(
      HttpVersion.HTTP_1_1,
      HttpResponseStatus.valueOf(httpResponse.status.code))
    httpResponse.headers.foreach { h =>
      val _ = response.headers().add(h.name.toString, h.value)
    }
    httpResponse.contentLength.foreach(len =>
      response.headers().set(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH, len))
    if (!response.headers().contains(io.netty.handler.codec.http.HttpHeaderNames.CONNECTION))
      response.headers().set(io.netty.handler.codec.http.HttpHeaderNames.CONNECTION, "close")
    writeAndFlushF(ctx, response)
  }

  /** Render a websocket response, or if the handshake fails eventually, an error Note: This
    * function is only invoked for http 1.1, as websockets aren't supported for http 1.0.
    *
    * @param httpRequest
    *   The incoming request
    * @param httpResponse
    *   The outgoing http4s reponse
    * @param httpVersion
    *   The calculated netty http version
    * @param wsContext
    *   the websocket context
    * @param dateString
    * @return
    */
  private[this] def toWSResponse(
      channel: ChannelHandlerContext,
      httpRequest: Request[F],
      httpResponse: Response[F],
      httpVersion: HttpVersion,
      wsContext: WebSocketContext[F],
      dateString: String,
      maxPayloadLength: Int
  ): Resource[F, DefaultHttpResponse] =
    if (httpRequest.headers.headers.exists(h =>
        h.name.toString.equalsIgnoreCase("Upgrade") && h.value.equalsIgnoreCase("websocket"))) {
      val wsProtocol = if (httpRequest.isSecure.exists(identity)) "wss" else "ws"
      val wsUrl = s"$wsProtocol://${httpRequest.serverAddr}${httpRequest.pathInfo}"
      val factory = new WebSocketServerHandshakerFactory(wsUrl, "*", true, maxPayloadLength)

      val receiveSend: Pipe[F, WebSocketFrame, WSFrame] =
        wsContext.webSocket match {
          case WebSocketSeparatePipe(send, receive, _) =>
            incoming =>
              send
                .concurrently(
                  incoming.through(receive).drain
                )
                .map(wsbitsToNetty) // We don't need to terminate if the send stream terminates.
          case WebSocketCombinedPipe(receiveSend, _) =>
            stream => receiveSend(stream).map(wsbitsToNetty)
        }

      val receiveSendWithClose: Pipe[F, WebSocketFrame, WSFrame] = input =>
        Stream.eval(Ref.of(false)).flatMap { closeFrameSent =>
          def close(closeFrame: WSFrame): F[Boolean] =
            for {
              modified <- closeFrameSent.modify(alreadySent => true -> !alreadySent)
              _ <-
                if (modified) {
                  Sync[F]
                    .delay(
                      channel.writeAndFlush(closeFrame).addListener(ChannelFutureListener.CLOSE))
                    .void
                } else {
                  Sync[F].delay(channel.close()).void
                }
            } yield modified

          val transformedInput = input
            .evalFilter {
              case closeFrame: Close => close(wsbitsToNetty(closeFrame))
              case _ => closeFrameSent.get.map(!_)
            }

          receiveSend(transformedInput)
            .evalFilterNot(_ => closeFrameSent.get)
            .evalFilter {
              case closeFrame: CloseWebSocketFrame =>
                close(closeFrame).as(false)
              case _ => true.pure[F]
            }
            .onFinalizeWeak(
              close(new CloseWebSocketFrame(WebSocketCloseStatus.NORMAL_CLOSURE)).void)
        }

      Resource
        .eval(StreamSubscriberWrapper.subscriber[F, WebSocketFrame](1))
        .flatMap { subscriber =>
          subscriber
            .stream(Sync[F].unit)
            .through(receiveSendWithClose)
            .onFinalize(wsContext.webSocket.onClose)
            .toPublisherResource
            .map { publisher =>
              val resp: DefaultHttpResponse =
                new DefaultWebSocketHttpResponse(
                  httpVersion,
                  HttpResponseStatus.OK,
                  processor(
                    FlowAdapters.toSubscriber(subscriber),
                    FlowAdapters.toPublisher(publisher)),
                  factory)
              wsContext.headers.foreach(appendAllToNetty(_, resp.headers()))
              resp
            }
        }
        .handleErrorWith((_: Throwable) =>
          Resource
            .eval(wsContext.failureResponse)
            .flatMap(res =>
              toNonWSResponse(httpRequest, res, httpVersion, dateString, minorVersionIs0 = true)))
    } else
      toNonWSResponse(httpRequest, httpResponse, httpVersion, dateString, minorVersionIs0 = true)

  /** Write a response (with possible WS upgrade) directly to the channel. Compiles the response
    * body stream in the handler's fiber rather than bridging through reactive streams, ensuring
    * body stream finalizers fire.
    */
  def writeResponseWithWebsocket(
      key: Key[WebSocketContext[F]],
      ctx: ChannelHandlerContext,
      httpRequest: Request[F],
      httpResponse: Response[F],
      dateString: String,
      maxPayloadLength: Int,
      awaitWritable: ChannelHandlerContext => F[Unit]
  ): F[Unit] = {
    val (httpVersion, minorIs0) = resolveHttpVersion(httpRequest.httpVersion)

    httpResponse.attributes.lookup(key) match {
      case Some(wsContext) if !minorIs0 =>
        writeWebSocketResponse(
          ctx,
          httpRequest,
          httpResponse,
          httpVersion,
          wsContext,
          dateString,
          maxPayloadLength)
      case _ =>
        writeResponse(
          ctx,
          httpRequest,
          httpResponse,
          httpVersion,
          dateString,
          minorIs0,
          awaitWritable)
    }
  }

  private def writeResponse(
      ctx: ChannelHandlerContext,
      httpRequest: Request[F],
      httpResponse: Response[F],
      httpVersion: HttpVersion,
      dateString: String,
      minorIs0: Boolean,
      awaitWritable: ChannelHandlerContext => F[Unit]
  ): F[Unit] =
    if (httpResponse.status.isEntityAllowed && httpRequest.method != Method.HEAD)
      writeBodyResponse(
        ctx,
        httpRequest,
        httpResponse,
        httpVersion,
        dateString,
        minorIs0,
        awaitWritable)
    else
      writeFullResponse(ctx, httpRequest, httpResponse, httpVersion, dateString, minorIs0)

  private def writeBodyResponse(
      ctx: ChannelHandlerContext,
      httpRequest: Request[F],
      httpResponse: Response[F],
      httpVersion: HttpVersion,
      dateString: String,
      minorIs0: Boolean,
      awaitWritable: ChannelHandlerContext => F[Unit]
  ): F[Unit] = {
    val headersResponse =
      new DefaultHttpResponse(httpVersion, HttpResponseStatus.valueOf(httpResponse.status.code))
    httpResponse.headers.foreach(appendSomeToNetty(_, headersResponse.headers()))
    addTransferOrContentLengthHeaders(httpResponse.headers, minorIs0, headersResponse.headers())
    addDateAndConnectionHeaders(headersResponse.headers(), httpRequest, dateString, minorIs0)

    // The body MUST be compiled (drained) so that stream finalizers fire —
    // e.g., onFinalize callbacks that return connections to upstream pools.
    // The entire drain is uncancelable to prevent cancellation from firing
    // before compile.drain opens the stream scope (which would skip finalizers).
    // When the channel is dead, the first chunk write fails immediately,
    // compile.drain errors, and finalizers fire — so the uncancelable window
    // is short in practice.
    F.uncancelable { _ =>
      writeAndFlushF(ctx, headersResponse).attempt.flatMap {
        case Right(()) =>
          F.guarantee(
            httpResponse.body.chunks
              .evalMap { chunk =>
                awaitWritable(ctx) *>
                  writeAndFlushF(ctx, new DefaultHttpContent(chunkToBytebuf(chunk)))
              }
              .compile
              .drain,
            writeAndFlushF(ctx, LastHttpContent.EMPTY_LAST_CONTENT).handleError(_ => ())
          ).handleErrorWith { e =>
            F.delay(logger.debug(e)("Error writing response body, closing channel")) *>
              F.delay(ctx.close()).void
          }
        case Left(e) =>
          // Headers write failed (channel already closed). Still drain the body
          // so that stream finalizers fire (e.g., returning connections to pools).
          httpResponse.body.compile.drain.handleError(_ => ()) *>
            F.delay(logger.debug(e)("Channel closed before headers could be sent")) *>
            F.delay(ctx.close()).void
      }
    }
  }

  private def writeFullResponse(
      ctx: ChannelHandlerContext,
      httpRequest: Request[F],
      httpResponse: Response[F],
      httpVersion: HttpVersion,
      dateString: String,
      minorIs0: Boolean
  ): F[Unit] = {
    val response = new DefaultFullHttpResponse(
      httpVersion,
      HttpResponseStatus.valueOf(httpResponse.status.code)
    )
    httpResponse.headers.foreach(appendSomeToNetty(_, response.headers()))
    if (httpRequest.method == Method.HEAD) {
      addHeadResponseHeaders(httpResponse, response.headers())
    }
    addDateAndConnectionHeaders(response.headers(), httpRequest, dateString, minorIs0)
    writeAndFlushF(ctx, response) *>
      // Drain the body so that stream finalizers fire even for responses
      // without entity bodies (HEAD, 204, 304, etc.). In proxy patterns
      // the body may carry onFinalize callbacks that return connections
      // to upstream pools — these must fire even when no body is written.
      httpResponse.body.compile.drain.handleError(_ => ())
  }

  private def writeWebSocketResponse(
      ctx: ChannelHandlerContext,
      httpRequest: Request[F],
      httpResponse: Response[F],
      httpVersion: HttpVersion,
      wsContext: WebSocketContext[F],
      dateString: String,
      maxPayloadLength: Int
  ): F[Unit] =
    toWSResponse(
      ctx,
      httpRequest,
      httpResponse,
      httpVersion,
      wsContext,
      dateString,
      maxPayloadLength)
      .use { resp =>
        resp match {
          case _: DefaultWebSocketHttpResponse =>
            // The WS handshake is performed by HttpStreamsServerHandler which
            // never completes the original ChannelPromise, so we must use
            // fire-and-forget here instead of writeAndFlushF.
            // Keep the Resource alive until the channel closes so the WS
            // reactive-streams processor continues to run.
            F.delay { val _ = ctx.writeAndFlush(resp) } *>
              F.async_[Unit] { cb =>
                val _ =
                  ctx.channel().closeFuture().addListener((_: ChannelFuture) => cb(Right(())))
              }
          case _ =>
            // Non-WS fallback (handshake failed): write via normal path.
            writeAndFlushF(ctx, resp)
        }
      }

  private def writeAndFlushF(ctx: ChannelHandlerContext, msg: AnyRef): F[Unit] =
    F.async_[Unit] { cb =>
      val _ = ctx.writeAndFlush(msg).addListener { (f: ChannelFuture) =>
        if (f.isSuccess) cb(Right(()))
        else cb(Left(f.cause()))
      }
    }

  private[this] def appendAllToNetty(header: Header.Raw, nettyHeaders: HttpHeaders) = {
    nettyHeaders.add(header.name.toString, header.value)
    ()
  }

  private def processor(subscriber: Subscriber[WebSocketFrame], publisher: Publisher[WSFrame]) =
    new Processor[WSFrame, WSFrame] {
      def onError(t: Throwable): Unit = subscriber.onError(t)

      def onComplete(): Unit = subscriber.onComplete()

      def onNext(t: WSFrame): Unit = subscriber.onNext(nettyWsToHttp4s(t))

      def onSubscribe(s: Subscription): Unit = subscriber.onSubscribe(s)

      def subscribe(s: Subscriber[_ >: WSFrame]): Unit =
        publisher.subscribe(s)
    }

  private[this] def wsbitsToNetty(w: WebSocketFrame): WSFrame =
    w match {
      case Text(str, last) => new TextWebSocketFrame(last, 0, str)
      case ZeroCopyBinaryText(data, last) =>
        // data.toArrayUnsafe to avoid copying the underlying array
        new TextWebSocketFrame(last, 0, Unpooled.wrappedBuffer(data.toArrayUnsafe))
      case Binary(data, last) =>
        new BinaryWebSocketFrame(last, 0, Unpooled.wrappedBuffer(data.toArray))
      case Ping(data) => new PingWebSocketFrame(Unpooled.wrappedBuffer(data.toArray))
      case Pong(data) => new PongWebSocketFrame(Unpooled.wrappedBuffer(data.toArray))
      case Continuation(data, last) =>
        new ContinuationWebSocketFrame(last, 0, Unpooled.wrappedBuffer(data.toArray))
      case Close(data) => new CloseWebSocketFrame(true, 0, Unpooled.wrappedBuffer(data.toArray))
      case _ => new CloseWebSocketFrame(true, 0)
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
      case _ => Close(1000, "unknown ws packet").toOption.get
    }
}
