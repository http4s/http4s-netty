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
import cats.effect.kernel.Outcome
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
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._

/** When a request body stream is actively being consumed and the request is released, `drainBody`
  * must not attempt a second subscription to the single-subscriber `HandlerPublisher`.
  */
class DrainBodyDoubleSubscribeTest extends CatsEffectSuite {

  /** A reactive-streams Publisher that only supports one subscriber, matching HandlerPublisher.
    * When completeAfterEmit = true - calls onComplete after emitting one chunk, simulating a small
    * finished upload. When completeAfterEmit = false - never emits, simulating a slow/large upload
    * in progress.
    */
  private class SingleSubscriberPublisher(completeAfterEmit: Boolean)
      extends Publisher[HttpContent] {
    private val hasSubscriber = new AtomicBoolean(false)

    override def subscribe(s: Subscriber[_ >: HttpContent]): Unit =
      if (!hasSubscriber.compareAndSet(false, true)) {
        // Per reactive-streams spec, onSubscribe must be called before onError.
        s.onSubscribe(new Subscription {
          override def request(n: Long): Unit = ()
          override def cancel(): Unit = ()
        })
        s.onError(new IllegalStateException("This publisher only supports one subscriber"))
      } else {
        s.onSubscribe(new Subscription {
          private val emitted = new AtomicBoolean(false)
          override def request(n: Long): Unit =
            if (completeAfterEmit && emitted.compareAndSet(false, true)) {
              val content =
                new DefaultHttpContent(Unpooled.wrappedBuffer(Array.fill(64)(42.toByte)))
              s.onNext(content)
              s.onComplete()
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

  /** Like SingleSubscriberPublisher, but counts every `subscribe` call so a test can assert it was
    * only invoked once even when no `onError` propagates back to the caller.
    */
  private class CountingSingleSubscriberPublisher extends Publisher[HttpContent] {
    val subscribeCount = new AtomicInteger(0)
    private val hasSubscriber = new AtomicBoolean(false)

    override def subscribe(s: Subscriber[_ >: HttpContent]): Unit = {
      subscribeCount.incrementAndGet()
      if (!hasSubscriber.compareAndSet(false, true)) {
        s.onSubscribe(new Subscription {
          override def request(n: Long): Unit = ()
          override def cancel(): Unit = ()
        })
        s.onError(new IllegalStateException("This publisher only supports one subscriber"))
      } else {
        s.onSubscribe(new Subscription {
          private val emitted = new AtomicBoolean(false)
          override def request(n: Long): Unit =
            if (emitted.compareAndSet(false, true)) {
              val content =
                new DefaultHttpContent(Unpooled.wrappedBuffer(Array.fill(64)(42.toByte)))
              s.onNext(content)
              s.onComplete()
            }
          override def cancel(): Unit = ()
        })
      }
    }
  }

  // Helper to see if any of the exceptions are the in the stack.
  private def isDoubleSubscribeError(t: Throwable): Boolean = {
    def loop(x: Throwable): Boolean =
      x != null &&
        ((x.getMessage != null && x.getMessage.contains("only supports one subscriber"))
          || loop(x.getCause))
    loop(t)
  }

  /** Deterministic racing using deferred and fibers.
    *
    * Thread A (route handler) starts compiling the body but is paused before evaluating the body
    * stream. Thread B (the request Resource finalizer) runs drainBody while A is paused. When A is
    * unpaused it cannot also subscribe as that would violate the requirements of a single
    * subscriber.
    *
    * We use a `Deferred` to make the interleaving deterministic. Under load the same interleaving
    * occurs naturally, just less reliably.
    */
  test("drainBody and body compile must not double-subscribe the request publisher") {
    val publisher = new CountingSingleSubscriberPublisher
    val channel = new EmbeddedChannel()
    val request = new DefaultStreamedHttpRequest(
      HttpVersion.HTTP_1_1,
      HttpMethod.PUT,
      "/test",
      publisher
    )
    val conversion = new NettyModelConversion[IO]

    conversion.fromNettyRequest(channel, request).allocated.flatMap { case (req, release) =>
      IO.deferred[Unit].flatMap { gate =>
        for {
          // Start the route's body consumer but block it before any Stream step evaluates.
          // This models the scheduler not yet having run the first IO step of the body compile.
          bodyFiber <- (gate.get *> req.body.compile.drain).attempt.start
          // While the route fiber is parked, run release. drainBody observes subscribed=false
          // and calls f.compile.drain, which subscribes to the publisher.
          releaseResult <- release.attempt
          // Unblock the route fiber. It now runs the body stream, which used to call subscribe AGAIN.
          _ <- gate.complete(())
          bodyOutcome <- bodyFiber.join.timeout(5.seconds)
          bodyResult <- bodyOutcome match {
            case Outcome.Succeeded(io) => io
            case Outcome.Errored(e) => IO.pure(Left(e))
            case Outcome.Canceled() => IO.pure(Left(new RuntimeException("body fiber cancelled")))
          }
        } yield {
          val errs = List(bodyResult, releaseResult).collect {
            case Left(e) if isDoubleSubscribeError(e) => e
          }
          assert(
            errs.isEmpty && publisher.subscribeCount.get() == 1,
            s"Double subscribe detected (subscribeCount=${publisher.subscribeCount.get()}). " +
              s"bodyResult=$bodyResult releaseResult=$releaseResult"
          )
        }
      }
    }
  }

  /** If route code compiles the body more than once, the second compile must fail cleanly
    * (state-machine raise) rather than corrupt the underlying single-subscriber publisher by
    * calling subscribe a second time.
    */
  test("compiling req.body twice fails cleanly without double-subscribing the publisher") {
    val publisher = new CountingSingleSubscriberPublisher
    val channel = new EmbeddedChannel()
    val request = new DefaultStreamedHttpRequest(
      HttpVersion.HTTP_1_1,
      HttpMethod.PUT,
      "/test",
      publisher
    )
    val conversion = new NettyModelConversion[IO]

    conversion.fromNettyRequest(channel, request).allocated.flatMap { case (req, release) =>
      for {
        firstResult <- req.body.compile.drain.attempt
        secondResult <- req.body.compile.drain.attempt
        releaseResult <- release.attempt
      } yield {
        assert(firstResult.isRight, s"first compile should succeed, got: $firstResult")
        assert(secondResult.isLeft, s"second compile should fail, got: $secondResult")
        val secondErr = secondResult.swap.toOption.get
        assert(
          !isDoubleSubscribeError(secondErr),
          s"second compile must NOT surface a publisher double-subscribe error, got: $secondErr"
        )
        assert(releaseResult.isRight, s"release should not throw, got: $releaseResult")
        assert(
          publisher.subscribeCount.get() == 1,
          s"publisher must be subscribed exactly once, got: ${publisher.subscribeCount.get()}"
        )
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
