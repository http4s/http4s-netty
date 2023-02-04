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

package org.http4s.netty
package client

import cats.Foldable
import cats.effect.kernel.Async
import cats.effect.kernel.Deferred
import cats.effect.std.Dispatcher
import cats.effect.std.Queue
import cats.syntax.all._
import com.typesafe.netty.HandlerPublisher
import io.netty.buffer.Unpooled
import io.netty.channel._
import io.netty.handler.codec.http.websocketx._
import org.http4s.client.websocket.WSConnection
import org.http4s.client.websocket.WSFrame
import org.http4s.netty.NettyModelConversion
import org.http4s.netty.client.Http4sWebsocketHandler.fromWSFrame
import org.http4s.netty.client.Http4sWebsocketHandler.toWSFrame
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import scodec.bits.ByteVector

import scala.concurrent.ExecutionContext

private[client] class Http4sWebsocketHandler[F[_]](
    handshaker: WebSocketClientHandshaker,
    queue: Queue[F, Either[Throwable, WSFrame]],
    closed: Deferred[F, Unit],
    dispatcher: Dispatcher[F],
    callback: (Either[Throwable, WSConnection[F]]) => Unit
)(implicit F: Async[F])
    extends SimpleUserEventChannelHandler[
      WebSocketClientProtocolHandler.ClientHandshakeStateEvent] {
  private val logger = org.log4s.getLogger

  override def channelActive(ctx: ChannelHandlerContext): Unit = void {
    super.channelActive(ctx)
    if (!ctx.channel().config().isAutoRead) {
      ctx.read()
    }
  }

  @SuppressWarnings(Array("deprecation"))
  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
    logger.warn("Handshake issued")
    super.exceptionCaught(ctx, cause)
  }

  override def eventReceived(
      ctx: ChannelHandlerContext,
      evt: WebSocketClientProtocolHandler.ClientHandshakeStateEvent): Unit =
    evt match {
      case WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_ISSUED =>
        logger.trace("Handshake issued")
      case WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE =>
        logger.trace("Handshake complete")
        ctx.read()

        def complete =
          closed.complete(()).void >> F.delay(ctx.close()).void

        val publisher = new HandlerPublisher(ctx.executor(), classOf[WebSocketFrame]) {
          override def requestDemand(): Unit = void {
            if (!ctx.channel().config().isAutoRead) {
              ctx.read()
            }
          }

          override def cancelled(): Unit =
            dispatcher.unsafeRunAndForget(complete)
        }
        ctx.pipeline().addBefore(ctx.name(), "stream-publisher", publisher)

        publisher.subscribe(new Subscriber[WebSocketFrame] {

          def isCloseFrame(ws: WSFrame) = ws.isInstanceOf[WSFrame.Close]

          override def onSubscribe(s: Subscription): Unit =
            s.request(Long.MaxValue)

          override def onNext(t: WebSocketFrame): Unit = void {
            val converted = toWSFrame(t)
            val offer = queue.offer(Right(converted))
            val op = if (isCloseFrame(converted)) {
              complete >> offer
            } else {
              offer
            }
            dispatcher.unsafeRunSync(op)
          }

          override def onError(t: Throwable): Unit = void {
            dispatcher.unsafeRunSync(complete >> queue.offer(Left(t)))
          }

          override def onComplete(): Unit = void {
            dispatcher.unsafeRunSync(complete)
          }
        })

        callback(new Conn(handshaker.actualSubprotocol(), ctx).asRight[Throwable])

      case WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_TIMEOUT =>
        callback(Left(new IllegalStateException("Handshake timeout")))
    }

  private class Conn(
      sub: String,
      ctx: ChannelHandlerContext
  ) extends WSConnection[F] {
    private val runInNetty = F.evalOnK(ExecutionContext.fromExecutor(ctx.executor()))

    override def send(wsf: WSFrame): F[Unit] =
      sendMany(List(wsf))

    override def sendMany[G[_], A <: WSFrame](wsfs: G[A])(implicit G: Foldable[G]): F[Unit] =
      if (ctx.channel().isOpen) {
        runInNetty(F.delay {
          if (ctx.channel().isOpen && ctx.channel().isWritable) {
            val list = wsfs.toList
            list.foreach(wsf => ctx.write(fromWSFrame(wsf)))
            ctx.flush()
          } else {
            dispatcher.unsafeRunAndForget(closed.complete(()))
          }
          ()
        })
      } else {
        closed.complete(()).void
      }

    override def receive: F[Option[WSFrame]] = closed.tryGet.flatMap {
      case Some(_) =>
        logger.trace("closing")
        F.delay(ctx.close()).void >> none[WSFrame].pure[F]
      case None =>
        queue.take.rethrow.map(_.some)
    }

    override def subprotocol: Option[String] = Option(sub)

    def close: F[Unit] =
      F.unit
  }
}

private[client] object Http4sWebsocketHandler {
  def toWSFrame(frame: WebSocketFrame): WSFrame =
    frame match {
      case t: TextWebSocketFrame => WSFrame.Text(t.text(), t.isFinalFragment)
      case p: PingWebSocketFrame =>
        WSFrame.Ping(
          ByteVector.apply(NettyModelConversion.bytebufToArray(p.content(), release = false)))
      case p: PongWebSocketFrame =>
        WSFrame.Pong(
          ByteVector.apply(NettyModelConversion.bytebufToArray(p.content(), release = false)))
      case b: BinaryWebSocketFrame =>
        WSFrame.Binary(
          ByteVector.apply(NettyModelConversion.bytebufToArray(b.content(), release = false)),
          b.isFinalFragment)
      case c: CloseWebSocketFrame => WSFrame.Close(c.statusCode(), c.reasonText())
      case _ => WSFrame.Close(1000, "Unknown websocket frame")
    }

  def fromWSFrame(frame: WSFrame): WebSocketFrame =
    frame match {
      case WSFrame.Text(data, last) => new TextWebSocketFrame(last, 0, data)
      case WSFrame.Ping(data) =>
        new PingWebSocketFrame(Unpooled.wrappedBuffer(data.toArray))
      case WSFrame.Pong(data) =>
        new PongWebSocketFrame(Unpooled.wrappedBuffer(data.toArray))
      case WSFrame.Binary(data, last) =>
        new BinaryWebSocketFrame(last, 0, Unpooled.wrappedBuffer(data.toArray))
      case WSFrame.Close(status, reason) => new CloseWebSocketFrame(status, reason)
    }
}
