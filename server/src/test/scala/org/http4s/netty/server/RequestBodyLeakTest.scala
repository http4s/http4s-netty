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
import io.netty.buffer.ByteBuf
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

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters._

class RequestBodyLeakTest extends CatsEffectSuite {

  private class OneChunkPublisher(chunk: ByteBuf) extends Publisher[HttpContent] {
    override def subscribe(s: Subscriber[_ >: HttpContent]): Unit =
      s.onSubscribe(new Subscription {
        private val emitted = new AtomicBoolean(false)
        override def request(n: Long): Unit =
          if (emitted.compareAndSet(false, true)) {
            s.onNext(new DefaultHttpContent(chunk))
            s.onComplete()
          }
        override def cancel(): Unit = ()
      })
  }

  private def scenario(consumeBody: Boolean) = {
    val chunk = Unpooled.buffer(64).writeBytes(Array.fill(64)(42.toByte))
    val request = new DefaultStreamedHttpRequest(
      HttpVersion.HTTP_1_1,
      HttpMethod.PUT,
      "/test",
      new OneChunkPublisher(chunk)
    )
    val conversion = new NettyModelConversion[IO]

    conversion.fromNettyRequest(new EmbeddedChannel(), request).allocated.flatMap {
      case (req, release) =>
        val consume = if (consumeBody) req.body.compile.drain else IO.unit
        consume *> release *> IO(chunk.refCnt())
    }
  }

  test("body consumed by the route: chunk is released") {
    scenario(consumeBody = true).assertEquals(0)
  }

  test("body ignored by the route (drainBody path): chunk is released") {
    scenario(consumeBody = false).assertEquals(0)
  }

  // --- partial consumption ---

  /** Models HandlerPublisher: emits on demand, and releases anything still buffered when the
    * subscriber cancels (HandlerPublisher.receivedCancel -> cleanup).
    */
  private class CancelReleasingPublisher(chunks: List[ByteBuf]) extends Publisher[HttpContent] {
    private val remaining = new ConcurrentLinkedQueue[ByteBuf](chunks.asJava)

    override def subscribe(s: Subscriber[_ >: HttpContent]): Unit =
      s.onSubscribe(new Subscription {
        override def request(n: Long): Unit = {
          var i = 0L
          while (i < n) {
            val b = remaining.poll()
            if (b eq null) { s.onComplete(); i = n }
            else { s.onNext(new DefaultHttpContent(b)); i += 1 }
          }
        }
        override def cancel(): Unit = {
          var b = remaining.poll()
          while (b ne null) { b.release(); b = remaining.poll() }
        }
      })
  }

  test("body only partially consumed by the route: no chunk leaks (regression guard)") {
    val chunks = List.fill(4)(Unpooled.buffer(4).writeBytes(Array.fill(4)(1.toByte)))
    val request = new DefaultStreamedHttpRequest(
      HttpVersion.HTTP_1_1,
      HttpMethod.PUT,
      "/test",
      new CancelReleasingPublisher(chunks)
    )
    val conversion = new NettyModelConversion[IO]

    conversion
      .fromNettyRequest(new EmbeddedChannel(), request)
      .allocated
      .flatMap { case (req, release) =>
        // Route reads only the first byte, then abandons the rest of the body.
        req.body.take(1).compile.drain *> release *> IO(chunks.map(_.refCnt()))
      }
      .assertEquals(List(0, 0, 0, 0))
  }
}
