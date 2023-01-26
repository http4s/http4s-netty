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
package server

import cats.Defer
import cats.effect.Async
import cats.effect.Resource
import cats.effect.std.Dispatcher
import cats.syntax.all._
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel._
import io.netty.handler.codec.TooLongFrameException
import io.netty.handler.codec.http._
import io.netty.handler.timeout.IdleStateEvent
import org.http4s.HttpApp
import org.http4s.netty.server.Http4sNettyHandler.RFC7231InstantFormatter
import org.http4s.server.ServiceErrorHandler
import org.http4s.server.websocket.WebSocketBuilder2
import org.log4s.getLogger

import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import scala.collection.mutable.{Queue => MutableQueue}
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.control.NoStackTrace
import scala.util.control.NonFatal

/** Netty request handler
  *
  * Adapted from PlayRequestHandler.scala in
  * https://github.com/playframework/playframework/blob/master/framework/src/play-netty-server
  *
  * Variables inside this handler are essentially local to a thread in the
  * MultithreadedEventLoopGroup, as they are not mutated anywhere else.
  *
  * A note about "lastResponseSent" to help understand: By reassigning the variable with a `flatMap`
  * (which doesn't require synchronization at all, since you can consider this handler essentially
  * single threaded), this means that, we can run the `handle` action asynchronously by forking it
  * into a thread in `handle`, all the while ensuring in-order writes for the handler thread by
  * attaching the callback using `flatMap`. This ensures we can schedule more work asynchronously by
  * streaming `lastResponseSent` like a FIFO asynchronous queue.
  *
  * P.s this class was named `MikuHandler`. Record of this will exist honor of the fallen glorious
  * module name `http4s-miku`, slain by a bolt of lightning thrown by Zeus during a battle of module
  * naming.
  */
private[netty] abstract class Http4sNettyHandler[F[_]](disp: Dispatcher[F])(implicit
    F: Async[F]
) extends ChannelInboundHandlerAdapter {
  import Http4sNettyHandler.InvalidMessageException

  // By using the Netty event loop assigned to this channel we get two benefits:
  //  1. We can avoid the necessary hopping around of threads since Netty pipelines will
  //     only pass events up and down from within the event loop to which it is assigned.
  //     That means calls to ctx.read(), and ct.write(..), would have to be trampolined otherwise.
  //  2. We get serialization of execution: the EventLoop is a serial execution queue so
  //     we can rest easy knowing that no two events will be executed in parallel.
  private[this] var eventLoopContext: ExecutionContext = _

  // This is used essentially as a queue, each incoming request attaches callbacks to this
  // and replaces it to ensure that responses are written out in the same order that they came
  // in.
  private[this] var lastResponseSent: Future[Unit] = Future.unit

  // We keep track of the cancellation tokens for all the requests in flight. This gives us
  // observability into the number of requests in flight and the ability to cancel them all
  // if the connection gets closed.
  private[this] val pendingResponses = MutableQueue.empty[() => Future[Unit]]

  // Compute the formatted date string only once per second, and cache the result.
  // This should help microscopically under load.
  private[this] var cachedDate: Long = Long.MinValue
  private[this] var cachedDateString: String = _

  protected val logger = getLogger

  /** Handle the given request. Note: Handle implementations fork into user ExecutionContext Returns
    * the cleanup action along with the drain action
    */
  def handle(
      channel: Channel,
      request: HttpRequest,
      dateString: String): Resource[F, DefaultHttpResponse]

  override def channelRead(ctx: ChannelHandlerContext, msg: Object): Unit = {
    logger.trace(s"channelRead: ctx = $ctx, msg = $msg")
    val newTick = System.currentTimeMillis() / 1000
    if (cachedDate < newTick) {
      cachedDateString = RFC7231InstantFormatter.format(Instant.ofEpochSecond(newTick))
      cachedDate = newTick
    }

    msg match {
      case req: HttpRequest =>
        val reqAndCleanup = handle(ctx.channel(), req, cachedDateString).allocated
        // Start execution of the handler.

        val (f, cancelRequest) = disp.unsafeToFutureCancelable(reqAndCleanup)
        pendingResponses.enqueue(cancelRequest)

        // This attaches all writes sequentially using
        // LastResponseSent as a queue. `eventLoopContext` ensures we do not
        // CTX switch the writes.
        lastResponseSent = lastResponseSent.flatMap[Unit] { _ =>
          f.transform {
            case Success((response, cleanup)) =>
              pendingResponses.dequeue()
              if (pendingResponses.isEmpty)
                // Since we've now gone down to zero, we need to issue a
                // read, in case we ignored an earlier read complete
                ctx.read()
              void {
                ctx
                  .writeAndFlush(response)
                  .addListener((_: ChannelFuture) => disp.unsafeRunAndForget(cleanup))
              }
              Success(())

            case Failure(NonFatal(e)) =>
              logger.warn(e)(
                "Error caught during service handling. Check the configured ServiceErrorHandler.")
              void {
                sendSimpleErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR)
              }
              Failure(e)

            case Failure(e) => // fatal: just let it go.
              Failure(e)
          }(eventLoopContext)
        }(eventLoopContext)

      case LastHttpContent.EMPTY_LAST_CONTENT =>
        // These are empty trailers... what do do???
        ()
      case msg =>
        logger.error(s"Invalid message type received, ${msg.getClass}")
        throw InvalidMessageException
    }
  }

  override def channelReadComplete(ctx: ChannelHandlerContext): Unit = void {
    logger.trace(s"channelReadComplete: ctx = $ctx")

    // The normal response to read complete is to issue another read,
    // but we only want to do that if there are no requests in flight,
    // this will effectively limit the number of in flight requests that
    // we'll handle by pushing back on the TCP stream, but it also ensures
    // we don't get in the way of the request body reactive streams,
    // which will be using channel read complete and read to implement
    // their own back pressure
    if (pendingResponses.isEmpty) {
      ctx.read()
    } else {
      // otherwise forward it, so that any handler publishers downstream
      // can handle it
      ctx.fireChannelReadComplete()
    }
  }

  @SuppressWarnings(Array("deprecation"))
  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = void {
    cause match {
      // IO exceptions happen all the time, it usually just means that the client has closed the connection before fully
      // sending/receiving the response.
      case e: IOException =>
        logger.trace(e)("Benign IO exception caught in Netty")
        ctx.channel().close()
      case e: TooLongFrameException =>
        logger.warn(e)("Handling TooLongFrameException")
        sendSimpleErrorResponse(ctx, HttpResponseStatus.REQUEST_URI_TOO_LONG)
      case InvalidMessageException =>
        sendSimpleErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR)
      case e =>
        logger.error(e)("Exception caught in Netty")
        ctx.channel().close()
    }
  }

  override def handlerAdded(ctx: ChannelHandlerContext): Unit =
    if (ctx.channel.isActive) {
      initHandler(ctx)
    }

  override def channelActive(ctx: ChannelHandlerContext): Unit = initHandler(ctx)

  override def userEventTriggered(ctx: ChannelHandlerContext, evt: scala.Any): Unit = void {
    evt match {
      case _: IdleStateEvent if ctx.channel().isOpen =>
        logger.trace(s"Closing connection due to idle timeout")
        ctx.close();
      case _ => super.userEventTriggered(ctx, evt)
    }
  }

  private[this] def initHandler(ctx: ChannelHandlerContext): Unit =
    // Guard against double initialization. It shouldn't matter, but might as well be safe.
    if (eventLoopContext == null) void {
      // Initialize our ExecutionContext
      eventLoopContext = ExecutionContext.fromExecutor(ctx.channel.eventLoop)

      // When the channel closes we want to cancel any pending dispatches.
      // Since the listener will be executed from the channels EventLoop everything is thread safe.
      ctx.channel.closeFuture.addListener { (_: ChannelFuture) =>
        logger.debug(
          s"Http channel to ${ctx.channel.remoteAddress} closed. Cancelling ${pendingResponses.length} responses.")
        pendingResponses.foreach(_.apply())
      }

      // AUTO_READ is off, so need to do the first read explicitly.
      // this method is called when the channel is registered with the event loop,
      // so ctx.read is automatically safe here w/o needing an isRegistered().
      ctx.read()
    }

  private[this] def sendSimpleErrorResponse(
      ctx: ChannelHandlerContext,
      status: HttpResponseStatus): ChannelFuture = {
    val response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status)
    response.headers().set(HttpHeaderNames.CONNECTION, "close")
    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, "0")
    ctx
      .writeAndFlush(response)
      .addListener(ChannelFutureListener.CLOSE)
  }
}

object Http4sNettyHandler {

  // `DateTimeFormatter` is immutable and thread safe, so we can share it.
  private val RFC7231InstantFormatter =
    DateTimeFormatter
      .ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz")
      .withLocale(Locale.US)
      .withZone(ZoneId.of("GMT"))

  private[netty] case object InvalidMessageException extends Exception with NoStackTrace

  private class WebsocketHandler[F[_]](
      appFn: WebSocketBuilder2[F] => HttpApp[F],
      serviceErrorHandler: ServiceErrorHandler[F],
      maxWSPayloadLength: Int,
      dispatcher: Dispatcher[F]
  )(implicit
      F: Async[F],
      D: Defer[F]
  ) extends Http4sNettyHandler[F](dispatcher) {

    private[this] val converter: ServerNettyModelConversion[F] = new ServerNettyModelConversion[F]

    override def handle(
        channel: Channel,
        request: HttpRequest,
        dateString: String
    ): Resource[F, DefaultHttpResponse] =
      Resource.eval(WebSocketBuilder2[F]).flatMap { b =>
        val app = appFn(b).run
        logger.trace("Http request received by netty: " + request)
        converter
          .fromNettyRequest(channel, request)
          .flatMap { req =>
            Resource
              .eval(D.defer(app(req)).recoverWith(serviceErrorHandler(req)))
              .flatMap(
                converter.toNettyResponseWithWebsocket(
                  b.webSocketKey,
                  req,
                  _,
                  dateString,
                  maxWSPayloadLength))
          }
      }
  }

  def websocket[F[_]: Async](
      app: WebSocketBuilder2[F] => HttpApp[F],
      serviceErrorHandler: ServiceErrorHandler[F],
      maxWSPayloadLength: Int,
      dispatcher: Dispatcher[F]
  ): Http4sNettyHandler[F] =
    new WebsocketHandler[F](app, serviceErrorHandler, maxWSPayloadLength, dispatcher)
}
