package fs2.interop.flow

import cats.syntax.all.*
import cats.effect.Async

import java.util.concurrent.Flow
import java.util.concurrent.Flow.Subscriber

class StreamSubscriberWrapper[F[_], A](ss: StreamSubscriber[F, A]) extends Subscriber[A] {
  override def onSubscribe(subscription: Flow.Subscription): Unit = ss.onSubscribe(subscription)

  override def onNext(item: A): Unit = ss.onNext(item)

  override def onError(throwable: Throwable): Unit = ss.onError(throwable)

  override def onComplete(): Unit = ss.onComplete()

  def stream(fu: F[Unit]): fs2.Stream[F, A] = ss.stream(fu)
}

object StreamSubscriberWrapper {

  def subscriber[F[_], A](chunkSize: Int)(implicit F: Async[F]): F[StreamSubscriberWrapper[F, A]] =
    StreamSubscriber.apply(chunkSize).map(sub => new StreamSubscriberWrapper[F, A](sub))
}
