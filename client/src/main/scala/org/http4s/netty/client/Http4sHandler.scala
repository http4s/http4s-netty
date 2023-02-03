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
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.timeout.IdleStateEvent
import org.http4s.Response
import org.http4s.client.RequestKey
import org.http4s.netty.NettyModelConversion

import java.io.IOException
import scala.concurrent.Promise

@Sharable
private[netty] class Http4sHandler[F[_]](dispatcher: Dispatcher[F])(implicit F: Async[F])
    extends ChannelInboundHandlerAdapter {

  private[this] val logger = org.log4s.getLogger
  private val modelConversion = new NettyModelConversion[F]
  private val promises = collection.mutable.Map[RequestKey, Promise[Resource[F, Response[F]]]]()

  private[netty] def createPromise(key: RequestKey): Promise[Resource[F, Response[F]]] =
    promises.getOrElseUpdate(key, Promise())

  override def isSharable: Boolean = true

  override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = void {
    msg match {
      case h: HttpResponse =>
        val attr = ctx.channel().attr(Http4sChannelPoolMap.attr)
        val promise = promises.getOrElseUpdate(attr.get(), Promise())

        val responseResourceF = modelConversion
          .fromNettyResponse(h)
          .map { case (res, cleanup) =>
            Resource.make(F.pure(res))(_ => cleanup(ctx.channel()))
          }
          .attempt
          .map { res =>
            promise.complete(res.toTry)
          }
        dispatcher.unsafeRunSync(responseResourceF)
        promises.remove(attr.get())
      case _ =>
        super.channelRead(ctx, msg)
    }
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
    val attr = ctx.channel().attr(Http4sChannelPoolMap.attr)
    val promise = promises.getOrElseUpdate(attr.get(), Promise())
    promise.failure(e)
    ctx.channel().close()
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
