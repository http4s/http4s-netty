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

private[netty] class Http4sHandler[F[_]](implicit F: ConcurrentEffect[F])
    extends ChannelInboundHandlerAdapter {
  type CB = (Either[Throwable, Resource[F, Response[F]]]) => Unit

  private[this] val logger = org.log4s.getLogger
  val modelConversion = new NettyModelConversion[F]()
  var callback: Option[CB] =
    None

  def withCallback(cb: CB) =
    callback = Some(cb)

  override def isSharable: Boolean = true

  override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit =
    (msg, callback) match {
      case (h: HttpResponse, Some(cb)) =>
        val POOL_KEY: AttributeKey[SimpleChannelPool] =
          AttributeKey.valueOf("io.netty.channel.pool.SimpleChannelPool")

        val maybePool = Option(ctx.channel().attr(POOL_KEY).get())
        F.runAsync(modelConversion.fromNettyResponse(h)) { either =>
          IO {
            cb(either.map { case (res, cleanup) =>
              Resource.make(F.pure(res))(_ =>
                cleanup(ctx.channel()).flatMap(_ =>
                  F.delay(maybePool.foreach { pool =>
                    pool.release(ctx.channel())
                  })))
            })
          }
        }.unsafeRunSync()
        //reset callback
        callback = None
      case _ =>
        super.channelRead(ctx, msg)
    }

  @SuppressWarnings(Array("deprecation"))
  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit =
    (cause, callback) match {
      // IO exceptions happen all the time, it usually just means that the client has closed the connection before fully
      // sending/receiving the response.
      case (e: IOException, Some(cb)) =>
        logger.trace(e)("Benign IO exception caught in Netty")
        cb(Left(e))
        callback = None
        ctx.channel().close(); ()
      case (e, Some(cb)) =>
        logger.error(e)("Exception caught in Netty")
        cb(Left(e))
        callback = None
        ctx.channel().close(); ()
      case (e, None) =>
        logger.error(e)("Exception caught in Netty, no callback registered")
        ctx.channel().close(); ()
    }

  override def userEventTriggered(ctx: ChannelHandlerContext, evt: scala.Any): Unit =
    evt match {
      case _: IdleStateEvent if ctx.channel().isOpen =>
        logger.trace(s"Closing connection due to idle timeout")
        ctx.channel().close(); ()
      case _ => super.userEventTriggered(ctx, evt)
    }

}
