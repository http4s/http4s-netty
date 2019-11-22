package example

import java.io.IOException

import cats.effect.{ConcurrentEffect, IO}
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.{ChannelFuture, ChannelFutureListener, ChannelHandlerContext, ChannelInboundHandlerAdapter}
import io.netty.handler.codec.TooLongFrameException
import io.netty.handler.codec.http.{DefaultHttpResponse, HttpHeaderNames, HttpRequest, HttpResponseStatus, HttpVersion}
import io.netty.handler.timeout.IdleStateEvent
import org.http4s.server.ServiceErrorHandler
import org.http4s.{HttpApp, HttpDate}

import scala.util.control.{NoStackTrace, NonFatal}

@Sharable
final class Http4sHandler[F[_]](app: HttpApp[F], errorHandler: ServiceErrorHandler[F])(implicit F: ConcurrentEffect[F])
    extends ChannelInboundHandlerAdapter {
  private val logger = org.log4s.getLogger

  override def channelActive(ctx: ChannelHandlerContext): Unit = {
    ctx.read(); ()
  }

  override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit =
    msg match {
      case req: HttpRequest =>
        Translate.toHttp4sRequest[F](req) match {
          case Left(error) =>
            exceptionCaught(ctx, error)
          case Right(request) =>
            try {
              F.runAsync(F.recoverWith(app(request))(errorHandler(request))) {
                  case Left(error) =>
                    IO(exceptionCaught(ctx, error))
                  case Right(response) =>
                    IO {
                      ctx.write(Translate.toNettyResponse(HttpDate.now, response))
                      ()
                    }
                }
                .unsafeRunSync()
            } catch {
              case NonFatal(e) => exceptionCaught(ctx, e)
            }
        }
      case _ => exceptionCaught(ctx, InvalidMessageException)
    }

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
      case e: IllegalArgumentException
          if Option(e.getMessage)
            .exists(_.contains("Header value contains a prohibited character")) =>
        // https://github.com/netty/netty/blob/netty-3.9.3.Final/src/main/java/org/jboss/netty/handler/codec/http/HttpHeaders.java#L1075-L1080
        logger.debug(e)("Handling Header value error")
        sendSimpleErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST); ()

      case InvalidMessageException =>
        sendSimpleErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR); ()
      case e =>
        logger.error(e)("Exception caught in Netty")
        ctx.channel().close(); ()
    }

  override def userEventTriggered(ctx: ChannelHandlerContext, evt: Any): Unit = evt match {
    case _: IdleStateEvent if ctx.channel().isOpen =>
      logger.trace(s"Closing connection due to idle timeout")
      ctx.channel().close(); ()
    case _ => super.userEventTriggered(ctx, evt)
  }

  private def sendSimpleErrorResponse(ctx: ChannelHandlerContext, status: HttpResponseStatus): ChannelFuture = {
    val response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, status)
    response.headers().set(HttpHeaderNames.CONNECTION, "close")
    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, "0")
    val f = ctx.channel().write(response)
    f.addListener(ChannelFutureListener.CLOSE)
    f
  }
}

case object InvalidMessageException extends Exception with NoStackTrace
