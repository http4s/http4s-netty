package org.http4s.netty

import cats.effect.{Async, ConcurrentEffect, Effect, Resource}
import cats.implicits._
import fs2.concurrent.Queue
import fs2.{Chunk, Stream}
import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.channel._
import io.netty.handler.codec.http.{DefaultHttpContent, HttpContent}

class HttpContentStream[F[_]](queue: Queue[F, HttpContent])(implicit F: Effect[F])
  extends ChannelInboundHandlerAdapter {
  def stream(ctx: ChannelHandlerContext): Stream[F, Byte] = {
    queue.dequeue
      .flatMap(content => Stream.evalUnChunk(HttpContentStream.chunk(ctx, content)))
      .onFinalizeWeak(ctx.delay(HttpContentStream.unregister(ctx)))
  }

  override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
    msg match {
      case h: HttpContent => ctx.unsafeRunSync(queue.offer1(h))
      case bf: ByteBuf    => ctx.unsafeRunSync(queue.offer1(new DefaultHttpContent(bf)))
      case obj => super.channelRead(ctx, obj)
    }
  }
}

object HttpContentStream {
  private[netty] val CachedEmpty: DefaultHttpContent =
    new DefaultHttpContent(Unpooled.EMPTY_BUFFER)

  def apply[F[_]: ConcurrentEffect](ctx: ChannelHandlerContext): F[HttpContentStream[F]] =
    ctx.run(Queue.unbounded[F, HttpContent].map(new HttpContentStream[F](_)))

  def name(ctx: ChannelHandlerContext) = s"${ctx.name()}-body-publisher"

  def unregister(ctx: ChannelHandlerContext) = {
    if (ctx.channel().isActive) discard(ctx.pipeline().remove(name(ctx))) else ()
  }

  def register[F[_]: ConcurrentEffect](ctx: ChannelHandlerContext): HttpContentStream[F] = {
    var streamer: Option[HttpContentStream[F]] = None
    ctx.unsafeRunSyncNoShift(
      apply(ctx).map { stream =>
        streamer = Some(stream)
        discard(ctx.pipeline().addAfter(ctx.name(), name(ctx), stream))
      }
    )
    streamer.getOrElse(sys.error("Unable to register content stream"))
  }

  def chunk[F[_]](ctx: ChannelHandlerContext, c: HttpContent)(implicit F: Async[F]) = {
    ctx.run(
      Resource
        .make[F, HttpContent](F.delay(c))(c => F.delay(c.release()).void)
        .use(c => F.delay(Chunk.bytes(c.content().array())))
    )
  }
}
