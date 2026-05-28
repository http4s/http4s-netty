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
import cats.effect.Deferred
import cats.effect.Resource
import cats.effect.std.Dispatcher
import cats.syntax.all._
import io.netty.channel._
import io.netty.handler.codec.TooLongFrameException
import io.netty.handler.codec.http._
import io.netty.handler.timeout.IdleStateEvent
import org.http4s.ParseFailure
import org.http4s.Response
import org.http4s.netty.server.Http4sNettyHandler.RFC7231InstantFormatter
import org.http4s.netty.server.Http4sNettyHandler.WritabilityGate
import org.http4s.server.ServiceErrorHandler
import org.http4s.server.websocket.WebSocketBuilder2
import org.log4s.getLogger

import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
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

  protected val writabilityGate: WritabilityGate[F] = new WritabilityGate[F]()

  /** Handle the given request. Implementations write the response directly to the channel via the
    * ChannelHandlerContext.
    */
  def handle(ctx: ChannelHandlerContext, request: HttpRequest, dateString: String): F[Unit]

  override def channelWritabilityChanged(ctx: ChannelHandlerContext): Unit = {
    if (ctx.channel().isWritable) {
      writabilityGate.signal(disp)
    }
    super.channelWritabilityChanged(ctx)
  }

  override def channelInactive(ctx: ChannelHandlerContext): Unit = {
    // Wake any fiber suspended on the writability gate so it can proceed
    // to writeAndFlushF, which will fail on the closed channel, triggering
    // the normal error/finalizer path.
    writabilityGate.signal(disp)
    super.channelInactive(ctx)
  }

  override def channelRead(ctx: ChannelHandlerContext, msg: Object): Unit = {
    logger.trace(s"channelRead: ctx = $ctx, msg = $msg")
    val newTick = System.currentTimeMillis() / 1000
    if (cachedDate < newTick) {
      cachedDateString = RFC7231InstantFormatter.format(Instant.ofEpochSecond(newTick))
      cachedDate = newTick
    }

    msg match {
      case req: HttpRequest =>
        val handleF = handle(ctx, req, cachedDateString)
        val (f, cancelRequest) = disp.unsafeToFutureCancelable(handleF)
        pendingResponses.enqueue(cancelRequest)

        // This attaches all writes sequentially using
        // LastResponseSent as a queue. `eventLoopContext` ensures we do not
        // CTX switch the writes.
        lastResponseSent = lastResponseSent.flatMap[Unit] { _ =>
          f.transform {
            case Success(()) =>
              pendingResponses.dequeue()
              if (pendingResponses.isEmpty)
                // Since we've now gone down to zero, we need to issue a
                // read, in case we ignored an earlier read complete
                void(ctx.read())
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

  /** Binary suspend/resume gate that bridges Netty's `channelWritabilityChanged` event to a
    * cats-effect fiber. Before each body chunk write, the fiber calls `awaitWritable`; if the
    * channel is not writable (write buffer exceeds the high watermark), the fiber suspends on a
    * `Deferred` until the channel drains below the low watermark and `signal` is called.
    *
    * Only one fiber writes at a time per connection (HTTP/1.1 responses are serialized by
    * `lastResponseSent`), so a single-waiter gate is sufficient.
    *
    * Uses `AtomicReference` rather than `Ref[F, _]` because the gate is constructed eagerly in the
    * handler constructor (Java-land, no `F` available).
    */
  private[server] final class WritabilityGate[F[_]](implicit F: Async[F]) {
    private val waiter: AtomicReference[Deferred[F, Unit]] =
      new AtomicReference[Deferred[F, Unit]]()

    /** Suspend the calling fiber until the channel is writable. If the channel is already writable
      * or inactive (closed), returns immediately — an inactive channel will fail on the subsequent
      * `writeAndFlushF`, which is the correct error path.
      */
    def awaitWritable(ctx: ChannelHandlerContext): F[Unit] =
      F.delay(ctx.channel().isWritable || !ctx.channel().isActive).flatMap { canProceed =>
        if (canProceed) F.unit
        else
          Deferred[F, Unit].flatMap { gate =>
            F.delay(waiter.set(gate)) *>
              // Double-check: the channel may have become writable (or closed) between our
              // check and setting the waiter — channelWritabilityChanged (or channelInactive)
              // would have fired finding no waiter. Re-check catches this race.
              F.delay(ctx.channel().isWritable || !ctx.channel().isActive).flatMap { canProceedNow =>
                if (canProceedNow) {
                  // Clear the waiter we just set — nobody will signal it.
                  F.delay { val _ = waiter.compareAndSet(gate, null) }
                } else
                  gate.get
              }
          }
      }

    /** Complete the pending waiter (if any), waking the suspended fiber. Called from the Netty
      * event loop thread via `channelWritabilityChanged`.
      */
    def signal(disp: Dispatcher[F]): Unit = {
      val gate = waiter.getAndSet(null)
      if (gate != null) {
        disp.unsafeRunAndForget(gate.complete(()))
      }
    }
  }

  private class WebsocketHandler[F[_]](
      appFn: WebSocketBuilder2[F] => HttpResource[F],
      serviceErrorHandler: ServiceErrorHandler[F],
      requestLineParseErrorHandler: Throwable => F[Response[F]],
      maxWSPayloadLength: Int,
      dispatcher: Dispatcher[F]
  )(implicit
      F: Async[F],
      D: Defer[Resource[F, *]]
  ) extends Http4sNettyHandler[F](dispatcher) {

    private[this] val converter: ServerNettyModelConversion[F] = new ServerNettyModelConversion[F]

    override def handle(
        ctx: ChannelHandlerContext,
        request: HttpRequest,
        dateString: String
    ): F[Unit] =
      Resource
        .eval(WebSocketBuilder2[F])
        .flatMap { b =>
          val app = appFn(b)
          logger.trace("Http request received by netty: " + request)
          converter
            .fromNettyRequest(ctx.channel(), request)
            .evalMap { req =>
              val pf = serviceErrorHandler(req)
              // The app is cancelable (via poll) so that idle timeouts
              // can interrupt slow handlers. Once the app returns a
              // response, the uncancelable boundary ensures the Resource
              // finalizer fires — critical for HttpResource routes like:
              //   client.run(backendReq)  // Resource release = connection release
              // Without uncancelable, cancellation between .allocated and
              // the F.guarantee would lose `release`, leaking upstream
              // connections. writeResponseWithWebsocket is also internally
              // uncancelable (writeBodyResponse always drains the body to
              // fire stream finalizers), so it completes even if the
              // channel is already closed.
              F.uncancelable { poll =>
                poll(
                  D.defer(app(req))
                    .recoverWith { case t if pf.isDefinedAt(t) => Resource.eval(pf(t)) }
                    .allocated
                ).flatMap { case (response, release) =>
                  F.guarantee(
                    converter.writeResponseWithWebsocket(
                      b.webSocketKey,
                      ctx,
                      req,
                      response,
                      dateString,
                      maxWSPayloadLength,
                      writabilityGate.awaitWritable),
                    release
                  )
                }
              }
            }
            .handleErrorWith {
              case e: ParseFailure =>
                Resource.eval(
                  requestLineParseErrorHandler(e)
                    .flatMap(converter.writeSimpleErrorResponse(ctx, _))
                )
              case e => Resource.eval(F.raiseError(e))
            }
        }
        .use_
  }

  def websocket[F[_]: Async](
      app: WebSocketBuilder2[F] => HttpResource[F],
      serviceErrorHandler: ServiceErrorHandler[F],
      requestLineParseErrorHandler: Throwable => F[Response[F]],
      maxWSPayloadLength: Int,
      dispatcher: Dispatcher[F]
  ): Http4sNettyHandler[F] =
    new WebsocketHandler[F](
      app,
      serviceErrorHandler,
      requestLineParseErrorHandler,
      maxWSPayloadLength,
      dispatcher)
}
