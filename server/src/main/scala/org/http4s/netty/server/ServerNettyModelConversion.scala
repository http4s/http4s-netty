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
import cats.effect.Resource
import cats.effect.kernel.Sync
import cats.implicits._
import com.typesafe.netty.http.DefaultWebSocketHttpResponse
import fs2.Pipe
import fs2.interop.reactivestreams._
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.handler.codec.http.DefaultHttpResponse
import io.netty.handler.codec.http.HttpHeaders
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory
import io.netty.handler.codec.http.websocketx.{WebSocketFrame => WSFrame}
import org.http4s.Header
import org.http4s.Request
import org.http4s.Response
import org.http4s.internal.tls._
import org.http4s.netty.NettyModelConversion
import org.http4s.netty.NettyModelConversion.bytebufToArray
import org.http4s.server.SecureSession
import org.http4s.server.ServerRequestKeys
import org.http4s.websocket.WebSocketCombinedPipe
import org.http4s.websocket.WebSocketContext
import org.http4s.websocket.WebSocketFrame
import org.http4s.websocket.WebSocketFrame._
import org.http4s.websocket.WebSocketSeparatePipe
import org.http4s.{HttpVersion => HV}
import org.http4s.netty.server.websocket.ZeroCopyBinaryText
import org.reactivestreams.Processor
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import org.typelevel.vault.Key
import org.typelevel.vault.Vault
import scodec.bits.ByteVector

import javax.net.ssl.SSLEngine

private[server] final class ServerNettyModelConversion[F[_]](implicit F: Async[F])
    extends NettyModelConversion[F] {
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

  /** Create a Netty response from the result */
  def toNettyResponseWithWebsocket(
      key: Key[WebSocketContext[F]],
      httpRequest: Request[F],
      httpResponse: Response[F],
      dateString: String,
      maxPayloadLength: Int
  ): Resource[F, DefaultHttpResponse] = {
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

    httpResponse.attributes.lookup(key) match {
      case Some(wsContext) if !minorIs0 =>
        toWSResponse(
          httpRequest,
          httpResponse,
          httpVersion,
          wsContext,
          dateString,
          maxPayloadLength)
      case _ =>
        toNonWSResponse(httpRequest, httpResponse, httpVersion, dateString, minorIs0)
    }
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

      Resource
        .eval(StreamSubscriber[F, WebSocketFrame](1))
        .flatMap { subscriber =>
          StreamUnicastPublisher(
            subscriber
              .stream(Sync[F].unit)
              .through(receiveSend)
              .onFinalizeWeak(wsContext.webSocket.onClose))
            .map { publisher =>
              val processor = new Processor[WSFrame, WSFrame] {
                def onError(t: Throwable): Unit = subscriber.onError(t)

                def onComplete(): Unit = subscriber.onComplete()

                def onNext(t: WSFrame): Unit = subscriber.onNext(nettyWsToHttp4s(t))

                def onSubscribe(s: Subscription): Unit = subscriber.onSubscribe(s)

                def subscribe(s: Subscriber[_ >: WSFrame]): Unit =
                  publisher.subscribe(s)
              }
              val resp: DefaultHttpResponse =
                new DefaultWebSocketHttpResponse(
                  httpVersion,
                  HttpResponseStatus.OK,
                  processor,
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

  private[this] def appendAllToNetty(header: Header.Raw, nettyHeaders: HttpHeaders) = {
    nettyHeaders.add(header.name.toString, header.value)
    ()
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
