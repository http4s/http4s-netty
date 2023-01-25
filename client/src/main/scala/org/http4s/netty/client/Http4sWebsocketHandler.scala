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
import org.reactivestreams.{Subscriber, Subscription}
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

  override def eventReceived(
      ctx: ChannelHandlerContext,
      evt: WebSocketClientProtocolHandler.ClientHandshakeStateEvent): Unit =
    evt match {
      case WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_ISSUED =>
        logger.trace("Handshake issued")
      case WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE =>
        logger.trace("Handshake complete")
        ctx.read()
        val publisher = new HandlerPublisher(ctx.executor(), classOf[WebSocketFrame])
        ctx.pipeline().addBefore(ctx.name(), "stream-publisher", publisher)
        ctx.pipeline().remove(this)
        publisher.subscribe(new Subscriber[WebSocketFrame] {

          def isCloseFrame(ws: WSFrame) = ws.isInstanceOf[WSFrame.Close]

          override def onSubscribe(s: Subscription): Unit =
            s.request(Long.MaxValue)

          override def onNext(t: WebSocketFrame): Unit = {
            val converted = toWSFrame(t)
            println("on next")
            val offer = queue.offer(Right(converted))
            val op = if (isCloseFrame(converted)) {
              println("Close frame")
              offer >> closed.complete(())
            } else {
              offer
            }
            dispatcher.unsafeRunAndForget(op)
            println("next after dispatching")
          }

          override def onError(t: Throwable): Unit = {
            println("on error")
            dispatcher.unsafeRunAndForget(
              queue.offer(Left(t)) >> closed.complete(()) >> F.delay(ctx.close()))
            println("on error after dispatching")
          }

          override def onComplete(): Unit = {
            println("on complete")
            dispatcher.unsafeRunAndForget(closed.complete(()))
            println("on complete after dispatching")
          }
        })

        callback(new Conn(handshaker.actualSubprotocol(), ctx, queue, closed).asRight[Throwable])

      case _ =>
        super.userEventTriggered(ctx, evt)
    }

  private class Conn(
      sub: String,
      ctx: ChannelHandlerContext,
      queue: Queue[F, Either[Throwable, WSFrame]],
      closed: Deferred[F, Unit])
      extends WSConnection[F] {
    private val runInNetty = F.evalOnK(ExecutionContext.fromExecutor(ctx.executor()))

    override def send(wsf: WSFrame): F[Unit] = {
      logger.trace(s"writing $wsf")
      runInNetty(F.delay {
        if (ctx.channel().isOpen && ctx.channel().isWritable) {
          ctx.writeAndFlush(fromWSFrame(wsf))
          ()
        }
      })
    }

    override def sendMany[G[_], A <: WSFrame](wsfs: G[A])(implicit G: Foldable[G]): F[Unit] =
      runInNetty(F.delay {
        if (ctx.channel().isOpen && ctx.channel().isWritable) {
          val list = wsfs.toList
          list.foreach(wsf => ctx.write(fromWSFrame(wsf)))
          ctx.flush()
        }
        ()
      })

    override def receive: F[Option[WSFrame]] = closed.tryGet.flatMap {
      case Some(_) =>
        println("WHAT THE HELL")
        logger.warn("closing")
        F.delay(ctx.close()).void >> none[WSFrame].pure[F]
      case None =>
        println("on recieve")
        queue.take.rethrow.map(_.some)
    }

    override def subprotocol: Option[String] = Option(sub)

    def close: F[Unit] =
      closed.complete(()).void
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
