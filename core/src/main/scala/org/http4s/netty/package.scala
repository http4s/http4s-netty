package org.http4s

import cats.implicits._
import cats.effect.{Async, Effect, IO}
import io.netty.channel.ChannelHandlerContext

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

package object netty {
  implicit class RunWith[F[_]](ctx: ChannelHandlerContext) {
    private val ec = ExecutionContext.fromExecutor(ctx.executor())

    def delay[A](a: => A)(implicit F: Async[F]): F[A] =
      run(F.delay(a))

    def run[A](fa: F[A])(implicit F: Async[F]): F[A] = {
      Async.shift(ec) *> fa
    }

    def unsafeRunSync[A](fa: F[A])(implicit F: Effect[F]): Unit =
      unsafeRunSyncNoShift(run(fa))
    def unsafeRunSyncDelayed[A](a: => A)(implicit F: Effect[F]): Unit =
      unsafeRunSyncNoShift(delay(a))

    def unsafeRunSyncNoShift[A](fa: F[A])(implicit F: Effect[F]): Unit = {
      F.runAsync(fa) {
        case Right(_)           => IO.unit
        case Left(NonFatal(ex)) => IO(ctx.fireExceptionCaught(ex)).void
        case Left(ex)           => IO.raiseError(ex)
      }
        .unsafeRunSync()
    }
  }
  private[netty] def discard[A](a: => A): Unit = {
    val _ = a
    ()
  }
}
