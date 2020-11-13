package org.http4s.netty

import cats.effect.{Async, ConcurrentEffect, Effect, Resource}
import cats.implicits._
import fs2.concurrent.Queue
import fs2.{Chunk, Pipe, Stream}
import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.channel._
import io.netty.handler.codec.http.{DefaultHttpContent, HttpContent}
import org.http4s.netty.StreamHandler.FSM

class HttpContentStream[F[_]](fsm: FSM[F, HttpContent], prefix: String)(implicit F: Effect[F])
    extends ChannelInboundHandlerAdapter {
  def stream(ctx: ChannelHandlerContext): Stream[F, Byte] =
    fsm.stream
      .through(HttpContentStream.chunkPipe)
      .onFinalizeWeak(F.delay(HttpContentStream.unregister(ctx, prefix)))

  override def channelRegistered(ctx: ChannelHandlerContext): Unit =
    ctx.unsafeRunSync(fsm.subscribe)

  override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit =
    msg match {
      case h: HttpContent =>
        println("CONTENT")
        ctx.unsafeRunSync(fsm.onMessage(h))
      case bf: ByteBuf =>
        println("Buf")
        ctx.unsafeRunSync(fsm.onMessage(new DefaultHttpContent(bf)))
      case obj =>
        println("OTHER")
        ctx.fireChannelRead(msg)
        ctx.read()
    }

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit =
    ctx.unsafeRunSync(fsm.onError(cause))

  override def channelReadComplete(ctx: ChannelHandlerContext): Unit =
    ctx.unsafeRunSync(fsm.onFinalize)
}

object HttpContentStream {
  private[netty] val CachedEmpty: DefaultHttpContent =
    new DefaultHttpContent(Unpooled.EMPTY_BUFFER)

  def apply[F[_]: ConcurrentEffect](
      ctx: ChannelHandlerContext,
      prefix: String): F[HttpContentStream[F]] =
    StreamHandler.fsm[F, HttpContent](ctx).map(fsm => new HttpContentStream[F](fsm, prefix))

  def pipelineName(prefix: String) = s"${prefix}-body-publisher"

  def unregister(ctx: ChannelHandlerContext, name: String) =
    if (ctx.channel().isActive) discard(ctx.pipeline().remove(pipelineName(name))) else ()

  def stream[F[_]: ConcurrentEffect](ctx: ChannelHandlerContext, name: String): Stream[F, Byte] =
    ctx
      .unsafeRunSync(apply(ctx, name).map { stream =>
        discard(ctx.pipeline().addAfter(name, pipelineName(name), stream))
        println(ctx.pipeline().toMap.keySet())
        stream
      })
      .stream(ctx)

  def chunk[F[_]](c: HttpContent)(implicit F: Async[F]) =
    c.asResource.map(toChunk).use(chunk => F.delay(chunk))

  def toChunk(content: HttpContent) = {
    val buf = content.content()
    val length = buf.readableBytes()
    val array = Array.ofDim[Byte](length)
    buf.readBytes(array)
    Chunk.bytes(array)
  }

  def chunkPipe[F[_]: Async]: Pipe[F, HttpContent, Byte] =
    _.flatMap(c => Stream.evalUnChunk(chunk(c)))
}
