package org.http4s.netty.server

import cats.Defer
import cats.effect.std.Dispatcher

import java.io.IOException
import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId}
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import cats.effect.{Async, Resource}
import cats.syntax.all._
import io.netty.channel.{ChannelInboundHandlerAdapter, _}
import io.netty.handler.codec.TooLongFrameException
import io.netty.handler.codec.http._
import io.netty.handler.timeout.IdleStateEvent
import org.http4s.HttpApp
import org.http4s.server.ServiceErrorHandler
import org.http4s.internal.Trampoline
import org.log4s.getLogger

import scala.concurrent.{Future, Promise}
import scala.util.control.{NoStackTrace, NonFatal}

/** Netty request handler
  *
  * Adapted from PlayRequestHandler.scala
  * in
  * https://github.com/playframework/playframework/blob/master/framework/src/play-netty-server
  *
  * Variables inside this handler are essentially local to a thread in the
  * MultithreadedEventLoopGroup, as they are not mutated anywhere else.
  *
  * A note about "lastResponseSent" to help understand:
  * By reassigning the variable with a `flatMap` (which doesn't require synchronization at all, since
  * you can consider this handler essentially single threaded), this means that, we can run the
  * `handle` action asynchronously by forking it into a thread in `handle`, all the while
  * ensuring in-order writes for the handler thread by attaching the callback using `flatMap`.
  * This ensures we can schedule more work asynchronously by streaming `lastResponseSent` like a
  * FIFO asynchronous queue.
  *
  * P.s this class was named `MikuHandler`. Record of this will exist honor of the fallen glorious module name
  * `http4s-miku`, slain by a bolt of lightning thrown by Zeus during a battle of module naming.
  */
private[netty] abstract class Http4sNettyHandler[F[_]](disp: Dispatcher[F])(implicit
    F: Async[F]
) extends ChannelInboundHandlerAdapter {
  import Http4sNettyHandler.InvalidMessageException

  // We keep track of whether there are requests in flight.  If there are, we don't respond to read
  // complete, since back pressure is the responsibility of the streams.
  private[this] val requestsInFlight = new AtomicLong()

  // This is used essentially as a queue, each incoming request attaches callbacks to this
  // and replaces it to ensure that responses are written out in the same order that they came
  // in.
  private[this] var lastResponseSent: Future[Unit] = Future.successful(())

  // Cache the formatter thread locally
  private[this] val RFC7231InstantFormatter =
    DateTimeFormatter
      .ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz")
      .withLocale(Locale.US)
      .withZone(ZoneId.of("GMT"))

  // Compute the formatted date string only once per second, and cache the result.
  // This should help microscopically under load.
  private[this] var cachedDate: Long = Long.MinValue
  private[this] var cachedDateString: String = _

  protected val logger = getLogger

  /** Handle the given request.
    * Note: Handle implementations fork into user ExecutionContext
    * Returns the cleanup action along with the drain action
    */
  def handle(
      channel: Channel,
      request: HttpRequest,
      dateString: String): Resource[F, DefaultHttpResponse]

  override def channelRead(ctx: ChannelHandlerContext, msg: Object): Unit = {
    logger.trace(s"channelRead: ctx = $ctx, msg = $msg")
    val newTick: Long = System.currentTimeMillis() / 1000
    if (cachedDate < newTick) {
      cachedDateString = RFC7231InstantFormatter.format(Instant.ofEpochSecond(newTick))
      cachedDate = newTick
    }

    msg match {
      case req: HttpRequest =>
        requestsInFlight.incrementAndGet()
        val p: Promise[(HttpResponse, F[Unit])] =
          Promise[(HttpResponse, F[Unit])]()

        val reqAndCleanup = handle(ctx.channel(), req, cachedDateString).allocated

        //Start execution of the handler.
        disp.unsafeRunAndForget(
          F.async_[(HttpResponse, F[Unit])] { cb =>
            try cb(Right(disp.unsafeRunSync(reqAndCleanup)))
            catch {
              case e: Throwable => cb(Left(e))
            }
          }.map(p.success))

        //This attaches all writes sequentially using
        //LastResponseSent as a queue. `trampoline` ensures we do not
        //CTX switch the writes.
        lastResponseSent = lastResponseSent.flatMap[Unit] { _ =>
          p.future
            .map[Unit] { case (response, cleanup) =>
              if (requestsInFlight.decrementAndGet() == 0)
                // Since we've now gone down to zero, we need to issue a
                // read, in case we ignored an earlier read complete
                ctx.read()
              ctx
                .writeAndFlush(response)
                .addListener((_: ChannelFuture) => disp.unsafeRunAndForget(cleanup))
              ()
            }(Trampoline)
            .recover[Unit] { case NonFatal(e) =>
              logger.warn(e)("Error caught during write action")
              sendSimpleErrorResponse(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE); ()
            }(Trampoline)
        }(Trampoline)
      case LastHttpContent.EMPTY_LAST_CONTENT =>
        //These are empty trailers... what do do???
        ()
      case msg =>
        logger.error(s"Invalid message type received, ${msg.getClass}")
        throw InvalidMessageException
    }
  }

  override def channelReadComplete(ctx: ChannelHandlerContext): Unit = {
    logger.trace(s"channelReadComplete: ctx = $ctx")

    // The normal response to read complete is to issue another read,
    // but we only want to do that if there are no requests in flight,
    // this will effectively limit the number of in flight requests that
    // we'll handle by pushing back on the TCP stream, but it also ensures
    // we don't get in the way of the request body reactive streams,
    // which will be using channel read complete and read to implement
    // their own back pressure
    if (requestsInFlight.get() == 0) {
      ctx.read(); ()
    } else {
      // otherwise forward it, so that any handler publishers downstream
      // can handle it
      ctx.fireChannelReadComplete(); ()
    }
  }

  @SuppressWarnings(Array("deprecation"))
  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit =
    cause match {
      // IO exceptions happen all the time, it usually just means that the client has closed the connection before fully
      // sending/receiving the response.
      case e: IOException =>
        logger.trace(e)("Benign IO exception caught in Netty")
        ctx.channel().close(); ()
      case e: TooLongFrameException =>
        logger.warn(e)("Handling TooLongFrameException")
        sendSimpleErrorResponse(ctx, HttpResponseStatus.REQUEST_URI_TOO_LONG); ()

      case InvalidMessageException =>
        sendSimpleErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR); ()
      case e =>
        logger.error(e)("Exception caught in Netty")
        ctx.channel().close(); ()
    }

  override def channelActive(ctx: ChannelHandlerContext): Unit = {
    // AUTO_READ is off, so need to do the first read explicitly.
    // this method is called when the channel is registered with the event loop,
    // so ctx.read is automatically safe here w/o needing an isRegistered().
    ctx.read(); ()
  }

  override def userEventTriggered(ctx: ChannelHandlerContext, evt: scala.Any): Unit =
    evt match {
      case _: IdleStateEvent if ctx.channel().isOpen =>
        logger.trace(s"Closing connection due to idle timeout")
        ctx.close(); ()
      case _ => super.userEventTriggered(ctx, evt)
    }

  private def sendSimpleErrorResponse(
      ctx: ChannelHandlerContext,
      status: HttpResponseStatus): ChannelFuture = {
    val response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, status)
    response.headers().set(HttpHeaderNames.CONNECTION, "close")
    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, "0")
    val f = ctx.channel().write(response)
    f.addListener(ChannelFutureListener.CLOSE)
    f
  }
}

object Http4sNettyHandler {
  private[netty] case object InvalidMessageException extends Exception with NoStackTrace

  private class DefaultHandler[F[_]](
      app: HttpApp[F],
      serviceErrorHandler: ServiceErrorHandler[F],
      dispatcher: Dispatcher[F]
  )(implicit
      F: Async[F],
      D: Defer[F]
  ) extends Http4sNettyHandler[F](dispatcher) {

    private[this] val converter: ServerNettyModelConversion[F] =
      new ServerNettyModelConversion[F](dispatcher)

    override def handle(
        channel: Channel,
        request: HttpRequest,
        dateString: String
    ): Resource[F, DefaultHttpResponse] = {
      logger.trace("Http request received by netty: " + request)
      converter
        .fromNettyRequest(channel, request)
        .evalMap { req =>
          D.defer(app(req))
            .recoverWith(serviceErrorHandler(req))
            .map(response => (converter.toNettyResponse(req, response, dateString)))
        }
    }
  }

  private class WebsocketHandler[F[_]](
      app: HttpApp[F],
      serviceErrorHandler: ServiceErrorHandler[F],
      maxWSPayloadLength: Int,
      dispatcher: Dispatcher[F]
  )(implicit
      F: Async[F],
      D: Defer[F]
  ) extends Http4sNettyHandler[F](dispatcher) {

    private[this] val converter: ServerNettyModelConversion[F] =
      new ServerNettyModelConversion[F](dispatcher)

    override def handle(
        channel: Channel,
        request: HttpRequest,
        dateString: String
    ): Resource[F, DefaultHttpResponse] = {
      logger.trace("Http request received by netty: " + request)
      converter
        .fromNettyRequest(channel, request)
        .evalMap { req =>
          D
            .defer(app(req))
            .recoverWith(serviceErrorHandler(req))
            .flatMap(converter.toNettyResponseWithWebsocket(req, _, dateString, maxWSPayloadLength))
        }
    }
  }

  def default[F[_]: Async](
      app: HttpApp[F],
      serviceErrorHandler: ServiceErrorHandler[F],
      dispatcher: Dispatcher[F]): Http4sNettyHandler[F] =
    new DefaultHandler[F](app, serviceErrorHandler, dispatcher)

  def websocket[F[_]: Async](
      app: HttpApp[F],
      serviceErrorHandler: ServiceErrorHandler[F],
      maxWSPayloadLength: Int,
      dispatcher: Dispatcher[F]
  ): Http4sNettyHandler[F] =
    new WebsocketHandler[F](app, serviceErrorHandler, maxWSPayloadLength, dispatcher)
}
