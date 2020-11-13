package org.http4s

import cats.implicits._
import cats.effect.{Async, Effect, IO, Resource, Sync}
import io.netty.channel.ChannelHandlerContext
import io.netty.util.ReferenceCounted

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

package object netty {
  implicit class RunWith[F[_]](ctx: ChannelHandlerContext) {
    private val ec = ExecutionContext.fromExecutor(ctx.executor())

    def delay[A](a: => A)(implicit F: Async[F]): F[A] =
      run(F.delay(a))

    def run[A](fa: F[A])(implicit F: Async[F]): F[A] =
      /*Async.shift(ec) *> */ fa

    def unsafeRunSync[A](fa: F[A])(implicit F: Effect[F]): A =
      F.toIO(fa).unsafeRunSync()
    def unsafeRunSyncDelayed[A](a: => A)(implicit F: Effect[F]): A =
      unsafeRunSyncNoShift(delay(a))

    def unsafeRunSyncNoShift[A](fa: F[A])(implicit F: Effect[F]): A =
      F.toIO(fa).unsafeRunSync()
  }
  private[netty] def discard[A](a: => A): Unit = {
    val _ = a
    ()
  }

  implicit class ReferenceCountedExt[A <: ReferenceCounted](underlying: A) {
    def asResource[F[_]: Sync] =
      Resource.make[F, A](Sync[F].delay(underlying.retain().asInstanceOf[A]))(a =>
        Sync[F].delay(a.release()))
  }
}
