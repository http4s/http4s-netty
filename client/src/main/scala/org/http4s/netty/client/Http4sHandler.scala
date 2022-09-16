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

import cats.effect.Async
import cats.effect.Resource
import cats.effect.std.Dispatcher
import cats.implicits._
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.timeout.IdleStateEvent
import org.http4s.Response
import org.http4s.netty.NettyModelConversion

import java.io.IOException

private[netty] class Http4sHandler[F[_]](cb: Http4sHandler.CB[F], dispatcher: Dispatcher[F])(
    implicit F: Async[F])
    extends ChannelInboundHandlerAdapter {

  private[this] val logger = org.log4s.getLogger
  val modelConversion = new NettyModelConversion[F](dispatcher)

  override def isSharable: Boolean = false

  override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit =
    msg match {
      case h: HttpResponse =>
        val responseResourceF = modelConversion
          .fromNettyResponse(h)
          .map { case (res, cleanup) =>
            Resource.make(F.pure(res))(_ => cleanup(ctx.channel()))
          }
          .attempt
          .map { res =>
            cb(res)
            ctx.pipeline().remove(this)
          }
        dispatcher.unsafeRunAndForget(responseResourceF)
      case _ =>
        super.channelRead(ctx, msg)
    }

  @SuppressWarnings(Array("deprecation"))
  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit =
    cause match {
      // IO exceptions happen all the time, it usually just means that the client has closed the connection before fully
      // sending/receiving the response.
      case e: IOException =>
        logger.trace(e)("Benign IO exception caught in Netty")
        onException(ctx, e)
      case e =>
        logger.error(e)("Exception caught in Netty")
        onException(ctx, e)
    }

  private def onException(ctx: ChannelHandlerContext, e: Throwable): Unit = void {
    cb(Left(e))
    ctx.channel().close()
    ctx.pipeline().remove(this)
  }

  override def userEventTriggered(ctx: ChannelHandlerContext, evt: scala.Any): Unit = void {
    evt match {
      case _: IdleStateEvent if ctx.channel().isOpen =>
        logger.trace(s"Closing connection due to idle timeout")
        ctx.channel().close()
      case _ => super.userEventTriggered(ctx, evt)
    }
  }
}

object Http4sHandler {
  type CB[F[_]] = (Either[Throwable, Resource[F, Response[F]]]) => Unit
}
