/*
 * Copyright 2020 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fs2.interop.flow

import cats.effect.Async
import cats.syntax.all._

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
