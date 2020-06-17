package org.http4s.netty.client

import java.io.IOException

import org.http4s.netty.NettyModelConversion
import cats.implicits._
import cats.effect.{ConcurrentEffect, IO}
import io.netty.channel.{Channel, ChannelHandlerContext, ChannelInboundHandlerAdapter}
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.timeout.IdleStateEvent
import io.netty.util.AttributeKey
import org.http4s.{Request, Response}
import org.http4s.client.RequestKey

private[netty] class Http4sHandler[F[_]](
    request: Request[F],
    key: RequestKey,
    cb: (Either[Throwable, (Channel, (Response[F], Channel => F[Unit]))]) => Unit)(implicit
    F: ConcurrentEffect[F])
    extends ChannelInboundHandlerAdapter {
  private[this] val logger = org.log4s.getLogger
  val modelConversion = new NettyModelConversion[F]()

  override def channelActive(ctx: ChannelHandlerContext): Unit = {
    val channel = ctx.channel()

    channel.writeAndFlush(modelConversion.toNettyRequest(request))
    ctx.read()
    ()
  }

  override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit =
    msg match {
      case h: HttpResponse if ctx.channel().attr(Http4sHandler.attributeKey).get() == key =>
        F.runAsync(modelConversion.fromNettyResponse(h)) { either =>
          IO(cb(either.tupleLeft(ctx.channel())))
        }.unsafeRunSync()
      case _ => super.channelRead(ctx, msg)
    }

  @SuppressWarnings(Array("deprecation"))
  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit =
    cause match {
      // IO exceptions happen all the time, it usually just means that the client has closed the connection before fully
      // sending/receiving the response.
      case e: IOException =>
        logger.trace(e)("Benign IO exception caught in Netty")
        cb(Left(e))
        ctx.channel().close(); ()
      case e =>
        logger.error(e)("Exception caught in Netty")
        cb(Left(e))
        ctx.channel().close(); ()
    }

  override def userEventTriggered(ctx: ChannelHandlerContext, evt: scala.Any): Unit =
    evt match {
      case _: IdleStateEvent if ctx.channel().isOpen =>
        logger.trace(s"Closing connection due to idle timeout")
        ctx.close(); ()
      case _ => super.userEventTriggered(ctx, evt)
    }

}

object Http4sHandler {
  val attributeKey = AttributeKey.newInstance[RequestKey](classOf[RequestKey].getName)
}
