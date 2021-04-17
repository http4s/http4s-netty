package org.http4s.netty.client

import cats.effect.std.Dispatcher

import java.io.IOException
import cats.implicits._
import cats.effect.{Async, Resource}
import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter}
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.timeout.IdleStateEvent
import org.http4s.Response
import org.http4s.netty.NettyModelConversion

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
        ()
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

  private def onException(ctx: ChannelHandlerContext, e: Throwable): Unit = {
    cb(Left(e))
    ctx.channel().close()
    ctx.pipeline().remove(this)
    ()
  }

  override def userEventTriggered(ctx: ChannelHandlerContext, evt: scala.Any): Unit =
    evt match {
      case _: IdleStateEvent if ctx.channel().isOpen =>
        logger.trace(s"Closing connection due to idle timeout")
        ctx.channel().close(); ()
      case _ => super.userEventTriggered(ctx, evt)
    }
}

object Http4sHandler {
  type CB[F[_]] = (Either[Throwable, Resource[F, Response[F]]]) => Unit
}
