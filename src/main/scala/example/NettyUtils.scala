package example

import cats.effect.Async
import io.netty.channel.ChannelFuture

import scala.concurrent.CancellationException

object NettyUtils {
  def toAsync[F[_]](future: ChannelFuture)(implicit F: Async[F]): F[Unit] =
    F.async { cb =>
      if (future.isDone) {
        cb(Right(()))
      } else if (future.isCancelled) {
        cb(Left(new CancellationException))
      } else {
        cb(Left(future.cause()))
      }
    }
}
