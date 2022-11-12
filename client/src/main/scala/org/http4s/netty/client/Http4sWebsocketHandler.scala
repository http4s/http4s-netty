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
import cats.effect.Resource
import cats.effect.kernel.Async
import cats.effect.kernel.Deferred
import cats.effect.std.Dispatcher
import cats.effect.std.Queue
import cats.syntax.all._
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http._
import io.netty.handler.codec.http.websocketx._
import org.http4s.client.websocket.WSConnection
import org.http4s.client.websocket.WSFrame
import org.http4s.client.websocket.WSRequest
import org.http4s.netty.NettyModelConversion
import org.http4s.netty.client.Http4sWebsocketHandler.fromWSFrame
import org.http4s.netty.client.Http4sWebsocketHandler.toWSFrame
import scodec.bits.ByteVector

import java.net.URI

private[client] class Http4sWebsocketHandler[F[_]](
    req: WSRequest,
    queue: Queue[F, Either[Throwable, WSFrame]],
    closed: Deferred[F, Unit],
    dispatcher: Dispatcher[F],
    callback: (Either[Throwable, Resource[F, WSConnection[F]]]) => Unit
)(implicit F: Async[F])
    extends SimpleChannelInboundHandler[AnyRef] {
  private val nettyConverter = new NettyModelConversion[F](dispatcher)

  private val handshaker = WebSocketClientHandshakerFactory.newHandshaker(
    URI.create(req.uri.renderString),
    WebSocketVersion.V13,
    "*",
    true,
    nettyConverter.toNettyHeaders(req.headers)
  )

  private val logger = org.log4s.getLogger

  override def channelRead0(ctx: ChannelHandlerContext, msg: Object): Unit = {
    val ch = ctx.channel()
    if (!handshaker.isHandshakeComplete) {
      try {
        msg match {
          case f: FullHttpResponse =>
            handshaker.finishHandshake(ch, f)
          case _ =>
            ctx.fireChannelRead(msg)
        }
        callback(Right {
          val conn = new Conn(handshaker.actualSubprotocol(), ctx, queue, closed)
          Resource(F.delay(conn -> conn.close))
        })
      } catch {
        case e: WebSocketHandshakeException =>
          callback(Left(e))
          dispatcher.unsafeRunAndForget(closed.complete(()))
      }
    } else {
      logger.trace("got> " + msg.getClass)
      void(msg match {
        case response: HttpResponse =>
          ctx.fireExceptionCaught(
            new IllegalStateException(s"Unexpected HttpResponse (getStatus='${response.status()}'"))
        case frame: CloseWebSocketFrame =>
          val op =
            queue.offer(Right(toWSFrame(frame))) >> closed.complete(()) >> F.cede
          dispatcher.unsafeRunAndForget(op)
        case frame: WebSocketFrame =>
          val op = queue.offer(Right(toWSFrame(frame)))
          dispatcher.unsafeRunAndForget(op)
        case _ =>
          ctx.fireChannelRead(msg)
      })
    }
  }

  override def channelActive(ctx: ChannelHandlerContext): Unit = {
    logger.trace("channel active")
    dispatcher.unsafeRunAndForget(F.delay(handshaker.handshake(ctx.channel())).liftToF)
  }

  @SuppressWarnings(Array("deprecated"))
  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = void {
    logger.error(cause)("something failed")
    callback(Left(cause))
    dispatcher.unsafeRunAndForget(queue.offer(Left(cause)) >> closed.complete(()) >> F.cede)
  }

  class Conn(
      sub: String,
      ctx: ChannelHandlerContext,
      queue: Queue[F, Either[Throwable, WSFrame]],
      closed: Deferred[F, Unit])
      extends WSConnection[F] {
    override def send(wsf: WSFrame): F[Unit] =
      F.delay(ctx.channel().isWritable)
        .ifM(
          {
            logger.info(s"writing $wsf")
            F.delay(ctx.writeAndFlush(fromWSFrame(wsf))).liftToF
          },
          F.unit)

    override def sendMany[G[_], A <: WSFrame](wsfs: G[A])(implicit G: Foldable[G]): F[Unit] =
      G.traverse_(wsfs)(send(_))

    override def receive: F[Option[WSFrame]] = closed.tryGet.flatMap {
      case Some(_) =>
        logger.trace("closing")
        F.delay(ctx.channel().isOpen)
          .ifM(F.delay(ctx.close).liftToF, F.unit)
          .as(none)
      case None =>
        queue.take
          .flatMap[Option[WSFrame]] {
            case Right(frame: WSFrame) => F.pure(frame.some)
            case Left(ex: Throwable) => F.raiseError(ex)
          }
          .flatTap(_ => F.cede)
    }

    override def subprotocol: Option[String] = Option(sub)

    def close: F[Unit] = F
      .delay(ctx.channel().isWritable)
      .ifM(send(WSFrame.Close(1000, "")) >> F.delay(ctx.close).liftToF, F.unit)
  }
}

private[client] object Http4sWebsocketHandler {
  def toWSFrame(frame: WebSocketFrame): WSFrame =
    frame match {
      case t: TextWebSocketFrame => WSFrame.Text(t.text(), t.isFinalFragment)
      case p: PingWebSocketFrame =>
        WSFrame.Ping(ByteVector.apply(NettyModelConversion.bytebufToArray(p.content())))
      case p: PongWebSocketFrame =>
        WSFrame.Pong(ByteVector.apply(NettyModelConversion.bytebufToArray(p.content())))
      case b: BinaryWebSocketFrame =>
        WSFrame.Binary(
          ByteVector.apply(NettyModelConversion.bytebufToArray(b.content())),
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
