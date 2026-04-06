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

package org.http4s.netty.server

import cats.effect.IO
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.DefaultHttpContent
import io.netty.handler.codec.http.HttpContent
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpVersion
import munit.CatsEffectSuite
import org.http4s.netty.NettyModelConversion
import org.playframework.netty.http.DefaultStreamedHttpRequest
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration._

/** When a request body stream is actively being consumed and the request is released, `drainBody`
  * must not attempt a second subscription to the single-subscriber `HandlerPublisher`.
  */
class DrainBodyDoubleSubscribeTest extends CatsEffectSuite {

  /** A reactive-streams Publisher that only supports one subscriber, matching HandlerPublisher When
    * completeAfterEmit = true - calls onComplete after emitting one chunk, simulating a small
    * finished upload. When completeAfterEmit = false - emits one chunk then blocks forever,
    * simulating a slow/large upload in progress.
    */
  private class SingleSubscriberPublisher(completeAfterEmit: Boolean)
      extends Publisher[HttpContent] {
    private val hasSubscriber = new AtomicBoolean(false)

    override def subscribe(s: Subscriber[_ >: HttpContent]): Unit =
      if (!hasSubscriber.compareAndSet(false, true)) {
        s.onError(new IllegalStateException("This publisher only supports one subscriber"))
      } else {
        s.onSubscribe(new Subscription {
          private val emitted = new AtomicBoolean(false)
          override def request(n: Long): Unit =
            if (emitted.compareAndSet(false, true)) {
              val content =
                new DefaultHttpContent(Unpooled.wrappedBuffer(Array.fill(64)(42.toByte)))
              s.onNext(content)
              if (completeAfterEmit) s.onComplete()
            }
          override def cancel(): Unit = ()
        })
      }
  }

  test(
    "ensure releasing while body is being consumed does not double subscribe"
  ) {
    val publisher = new SingleSubscriberPublisher(false)
    val channel = new EmbeddedChannel()
    val request = new DefaultStreamedHttpRequest(
      HttpVersion.HTTP_1_1,
      HttpMethod.PUT,
      "/test",
      publisher
    )
    val conversion = new NettyModelConversion[IO]

    // Use .allocated so we control exactly when the drainBody fires.
    conversion.fromNettyRequest(channel, request).allocated.flatMap { case (req, release) =>
      // The fiber subscribes to the publisher and blocks waiting for more data (which never arrives).
      // This keeps the subscription active when we manually trigger release below.
      req.body.compile.drain.start.flatMap { fiber =>
        // Ensure the fiber has time to subscribe
        IO.sleep(200.millis) *>
          // Manually release
          release.attempt.flatMap { result =>
            // clean up the blocked fiber
            fiber.cancel *>
              IO {
                assert(
                  result.isRight,
                  s"Resource release should not throw, but got: ${result}"
                )
              }
          }
      }
    }
  }

  test("ensure releasing request Resource when body was never consumed drains without error") {
    val publisher = new SingleSubscriberPublisher(completeAfterEmit = true)
    val channel = new EmbeddedChannel()
    val request = new DefaultStreamedHttpRequest(
      HttpVersion.HTTP_1_1,
      HttpMethod.PUT,
      "/test",
      publisher
    )
    val conversion = new NettyModelConversion[IO]

    // Body is never consumed. drainBody should subscribe to drain.
    conversion.fromNettyRequest(channel, request).allocated.flatMap { case (_, release) =>
      release.timeout(5.seconds).attempt.map { result =>
        assert(result.isRight, s"Resource release should not throw, but got: ${result}")
      }
    }
  }
}
