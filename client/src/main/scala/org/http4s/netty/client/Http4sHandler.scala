package org.http4s.netty.client

import java.io.IOException

import cats.implicits._
import cats.effect.{ConcurrentEffect, IO, Resource}
import io.netty.channel.pool.SimpleChannelPool
import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter}
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.timeout.IdleStateEvent
import io.netty.util.AttributeKey
import org.http4s.Response
import org.http4s.netty.NettyModelConversion

private[netty] class Http4sHandler[F[_]](cb: Http4sHandler.CB[F])(implicit F: ConcurrentEffect[F])
    extends ChannelInboundHandlerAdapter {

  val POOL_KEY: AttributeKey[SimpleChannelPool] =
    AttributeKey.valueOf("io.netty.channel.pool.SimpleChannelPool")

  private[this] val logger = org.log4s.getLogger
  val modelConversion = new NettyModelConversion[F]()

  override def isSharable: Boolean = false

  override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit =
    msg match {
      case h: HttpResponse =>
        val maybePool = Option(ctx.channel().attr(POOL_KEY).get())
        val responseResourceF = modelConversion.fromNettyResponse(h).map { case (res, cleanup) =>
          Resource.make(F.pure(res))(_ =>
            cleanup(ctx.channel()).flatMap(_ =>
              F.delay(maybePool.foreach { pool =>
                pool.release(ctx.channel())
              })))
        }

        F.runAsync(responseResourceF) { either =>
          IO {
            cb(either)
            ctx.pipeline().remove(this)
            ()
          }
        }.unsafeRunSync()
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
    ctx.channel().close();
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
