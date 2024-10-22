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
import cats.effect.implicits.*
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import io.netty.channel.*
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.timeout.IdleState
import io.netty.handler.timeout.IdleStateEvent
import org.http4s.*
import org.http4s.client.RequestKey
import org.http4s.netty.client.Http4sHandler.logger

import java.io.IOException
import java.nio.channels.ClosedChannelException
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.TimeoutException
import scala.util.Failure
import scala.util.Success

private[netty] class Http4sHandler[F[_]](dispatcher: Dispatcher[F])(implicit F: Async[F])
    extends ChannelInboundHandlerAdapter {
  type Promise = Either[Throwable, Resource[F, Response[F]]] => Unit

  private val modelConversion = new NettyModelConversion[F]
  private val promises =
    collection.mutable.Queue[Promise]()
  // By using the Netty event loop assigned to this channel we get two benefits:
  //  1. We can avoid the necessary hopping around of threads since Netty pipelines will
  //     only pass events up and down from within the event loop to which it is assigned.
  //     That means calls to ctx.read(), and ct.write(..), would have to be trampolined otherwise.
  //  2. We get serialization of execution: the EventLoop is a serial execution queue so
  //     we can rest easy knowing that no two events will be executed in parallel.
  private var eventLoopContext: ExecutionContext = _

  private var pending: Future[Unit] = Future.unit
  private var inFlight: Option[Promise] = None

  private def write2(request: Request[F], channel: Channel, key: Key): F[Unit] = {
    import io.netty.handler.codec.http2._

    val convertedHeaders = modelConversion.toNettyHeaders(request.headers)
    val http2Headers = new DefaultHttp2Headers()
    http2Headers
      .method(request.method.name)
      .authority(key.requestKey.authority.renderString)
      .scheme(key.requestKey.scheme.value)
      .path(request.uri.toOriginForm.renderString)
    HttpConversionUtil.toHttp2Headers(convertedHeaders, http2Headers)

    val headersFrame = new DefaultHttp2HeadersFrame(
      http2Headers,
      NettyModelConversion.notAllowedWithBody.contains(request.method))

    def endOfStream: F[Unit] = request.trailerHeaders.flatMap { headers =>
      val trail =
        if (headers.isEmpty) new DefaultHttp2DataFrame(true)
        else {
          new DefaultHttp2HeadersFrame(
            HttpConversionUtil.toHttp2Headers(modelConversion.toNettyHeaders(headers), false),
            true)
        }
      F.delay(Http4sHandler.writeInEventLoop(trail, channel, key.requestKey)(onException))
    }

    val body = if (!headersFrame.isEndStream) {
      (request.body.chunks
        .evalMap(chunk =>
          F.delay(
            Http4sHandler.writeInEventLoop(
              new DefaultHttp2DataFrame(NettyModelConversion.chunkToBytebuf(chunk), false),
              channel,
              key.requestKey)(onException))) ++ fs2.Stream.eval(endOfStream)).compile.drain
    } else F.unit

    F.delay(
      Http4sHandler.writeInEventLoop(headersFrame, channel, key.requestKey)(onException)) >> body
  }

  private[client] def dispatch(
      request: Request[F],
      channel: Channel,
      key: Key): Resource[F, Response[F]] =
    Util.runInVersion(
      key.version,
      on2 = F
        .async[Resource[F, Response[F]]] { cb =>
          promises.enqueue(cb)
          dispatcher.unsafeRunAndForget(write2(request, channel, key))
          F.pure(Some(F.unit))
        }
        .toResource
        .flatMap(identity),
      on1 = modelConversion
        .toNettyRequest(request)
        .evalMap { nettyRequest =>
          F.async[Resource[F, Response[F]]] { cb =>
            promises.enqueue(cb)
            Http4sHandler.writeInEventLoop(nettyRequest, channel, key.requestKey)(onException)
            F.pure(Some(F.unit))
          }
        }
        .flatMap(identity)
    )
  override def isSharable: Boolean = false

  override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = void {
    implicit val ec: ExecutionContext = eventLoopContext

    msg match {
      case h: HttpResponse =>
        val responseResourceF = modelConversion
          .fromNettyResponse(h)
          .map { case (res, cleanup) =>
            Resource.make(F.pure(res))(_ => cleanup(ctx.channel()))
          }
        val result = dispatcher.unsafeToFuture(responseResourceF)

        if (promises.nonEmpty) {
          val promise = promises.dequeue()
          inFlight = Some(promise)
          logger.trace("dequeuing promise")
          pending = pending.flatMap { _ =>
            result.transform {
              case Failure(exception) =>
                logger.trace("handling promise failure")
                promise(Left(exception))
                inFlight = None
                Failure(exception)
              case Success(res) =>
                logger.trace("handling promise success")
                promise(Right(res))
                inFlight = None
                Success(())
            }
          }
        }
      case _ =>
        super.channelRead(ctx, msg)
    }
  }

  override def handlerAdded(ctx: ChannelHandlerContext): Unit =
    if (eventLoopContext == null) void {
      // Initialize our ExecutionContext
      eventLoopContext = ExecutionContext.fromExecutor(ctx.channel.eventLoop)
    }

  override def channelInactive(ctx: ChannelHandlerContext): Unit =
    onException(ctx.channel(), new ClosedChannelException())

  @SuppressWarnings(Array("deprecation"))
  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit =
    cause match {
      // IO exceptions happen all the time, it usually just means that the client has closed the connection before fully
      // sending/receiving the response.
      case e: IOException =>
        logger.trace(e)("Benign IO exception caught in Netty")
        onException(ctx.channel(), e)
      case e =>
        logger.error(e)("Exception caught in Netty")
        onException(ctx.channel(), e)
    }

  private def onException(channel: Channel, e: Throwable): Unit = void {
    implicit val ec: ExecutionContext = eventLoopContext

    val allPromises =
      (inFlight.toList ++ promises.dequeueAll(_ => true)).map(promise => Future(promise(Left(e))))
    logger.trace(s"onException: dequeueAll(${allPromises.size})")
    pending = pending.flatMap(_ => Future.sequence(allPromises).map(_ => ()))
    inFlight = None

    channel.close()
  }

  override def userEventTriggered(ctx: ChannelHandlerContext, evt: scala.Any): Unit = void {
    evt match {
      case e: IdleStateEvent if ctx.channel().isOpen =>
        val state = e.state()
        state match {
          case IdleState.READER_IDLE =>
            val message = "Timing out request due to missing read"
            onException(ctx.channel(), new TimeoutException(message))
          case IdleState.WRITER_IDLE => ()
          case IdleState.ALL_IDLE =>
            val message = "Closing connection due to idle timeout"
            logger.trace(message)
            onException(ctx.channel(), new TimeoutException(message))
        }
      case _ => super.userEventTriggered(ctx, evt)
    }
  }
}

private object Http4sHandler {
  private val logger = org.log4s.getLogger

  private[client] def writeInEventLoop(event: AnyRef, channel: Channel, key: RequestKey)(
      onException: (Channel, Throwable) => Unit) =
    if (channel.eventLoop().inEventLoop) {
      safedispatch(event, channel, key)(onException)
    } else {
      channel
        .eventLoop()
        .execute(() => safedispatch(event, channel, key)(onException))
    }

  private def safedispatch(event: AnyRef, channel: Channel, key: RequestKey)(
      onException: (Channel, Throwable) => Unit): Unit = void {
    // always enqueue
    if (channel.isActive) {
      logger.trace(s"ch $channel: sending ${event} to $key")
      // The voidPromise lets us receive failed-write signals from the
      // exceptionCaught method.
      channel.writeAndFlush(event, channel.voidPromise)
      logger.trace(s"ch $channel: after ${event} to $key")
    } else {
      // make sure we call all enqueued promises
      logger.info(s"ch $channel: message dispatched by closed channel to destination $key.")
      onException(channel, new ClosedChannelException)
    }
  }
}
