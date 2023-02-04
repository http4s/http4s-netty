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
import io.netty.channel._
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.timeout.IdleStateEvent
import org.http4s._
import org.http4s.client.RequestKey
import org.http4s.netty.NettyModelConversion

import java.io.IOException
import java.nio.channels.ClosedChannelException
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

private[netty] class Http4sHandler[F[_]](dispatcher: Dispatcher[F])(implicit F: Async[F])
    extends ChannelInboundHandlerAdapter {

  private val logger = org.log4s.getLogger
  private val modelConversion = new NettyModelConversion[F]
  private val promises =
    collection.mutable.Queue[Either[Throwable, Resource[F, Response[F]]] => Unit]()
  // By using the Netty event loop assigned to this channel we get two benefits:
  //  1. We can avoid the necessary hopping around of threads since Netty pipelines will
  //     only pass events up and down from within the event loop to which it is assigned.
  //     That means calls to ctx.read(), and ct.write(..), would have to be trampolined otherwise.
  //  2. We get serialization of execution: the EventLoop is a serial execution queue so
  //     we can rest easy knowing that no two events will be executed in parallel.
  private var eventLoopContext: ExecutionContext = _
  private var ctx: ChannelHandlerContext = _

  private var pending: Future[Unit] = Future.unit

  private[netty] def dispatch(request: Request[F]): Resource[F, Response[F]] = {
    val key = RequestKey.fromRequest(request)

    modelConversion
      .toNettyRequest(request)
      .evalMap { nettyRequest =>
        F.async[Resource[F, Response[F]]] { cb =>
          if (ctx.channel().isActive) {
            if (ctx.executor().inEventLoop()) {
              safedispatch(nettyRequest, key, cb).run()
            } else {
              ctx
                .executor()
                .submit(safedispatch(nettyRequest, key, cb))
            }
          } else {
            cb(Left(new ClosedChannelException))
          }
          F.delay(Some(F.delay(ctx.close()).void))
        }
      }
      .flatMap(identity)
  }

  private def safedispatch(
      request: HttpRequest,
      key: RequestKey,
      callback: Either[Throwable, Resource[F, Response[F]]] => Unit): Runnable = () =>
    void {
      promises.enqueue(callback)
      logger.trace(s"Sending request to $key")
      ctx.writeAndFlush(request).sync()
      logger.trace(s"After request to $key")
    }

  override def isSharable: Boolean = false

  override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = void {
    msg match {
      case h: HttpResponse =>
        val responseResourceF = modelConversion
          .fromNettyResponse(h)
          .map { case (res, cleanup) =>
            Resource.make(F.pure(res))(_ => cleanup(ctx.channel()))
          }
        val result = dispatcher.unsafeToFuture(responseResourceF)

        pending = pending.flatMap { _ =>
          val promise = promises.dequeue()
          result.transform {
            case Failure(exception) =>
              promise(Left(exception))
              Failure(exception)
            case Success(res) =>
              promise(Right(res))
              Success(())
          }(eventLoopContext)
        }(eventLoopContext)
      case _ =>
        super.channelRead(ctx, msg)
    }
  }

  override def handlerAdded(ctx: ChannelHandlerContext): Unit =
    if (eventLoopContext == null) void {
      // Initialize our ExecutionContext
      eventLoopContext = ExecutionContext.fromExecutor(ctx.channel.eventLoop)
      this.ctx = ctx
    }

  override def channelInactive(ctx: ChannelHandlerContext): Unit =
    onException(ctx, new ClosedChannelException())

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
    promises.foreach(cb => cb(Left(e)))
    promises.clear()
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
