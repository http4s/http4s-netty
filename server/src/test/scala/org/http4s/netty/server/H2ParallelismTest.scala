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
import cats.effect.Resource
import cats.syntax.all._
import munit.CatsEffectSuite
import org.eclipse.jetty.client.HttpClient
import org.eclipse.jetty.http2.client.HTTP2Client
import org.eclipse.jetty.http2.client.transport.HttpClientTransportOverHTTP2
import org.http4s.HttpRoutes
import org.http4s.dsl.io._
import org.http4s.server.Server

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._

/** Verify that HTTP/2 requests on the same connection are processed in parallel.
  *
  * HTTP/2 multiplexes streams over a single connection, so a slow request must not block a fast
  * one. This is a regression guard for the pipelining serialization fix in Http4sNettyHandler:
  * HTTP/1.1 requests are now serialized, but HTTP/2 streams (each with their own child channel and
  * handler instance) must remain independent.
  */
class H2ParallelismTest extends CatsEffectSuite {

  private def serverResource: Resource[IO, Server] = {
    val routes = HttpRoutes
      .of[IO] {
        case GET -> Root / "slow" =>
          IO.sleep(500.millis) *> Ok("slow")
        case GET -> Root / "fast" =>
          Ok("fast")
      }
      .orNotFound

    NettyServerBuilder[IO]
      .withHttpApp(routes)
      .withEventLoopThreads(2)
      .withShutdownTimeout(1.second)
      .withoutBanner
      .bindAny()
      .resource
  }

  private def h2ClientResource: Resource[IO, HttpClient] =
    Resource.fromAutoCloseable(IO.blocking {
      val client = new HttpClient(new HttpClientTransportOverHTTP2(new HTTP2Client()))
      client.setMaxConnectionsPerDestination(1)
      client.start()
      client
    })

  test("H2 prior knowledge: fast request completes before slow request on same connection") {
    (serverResource, h2ClientResource).tupled.use { case (server, client) =>
      IO.blocking {
        // Track completion order: first to complete gets 1, second gets 2
        val order = new AtomicInteger(0)
        val slowOrder = new CompletableFuture[Int]()
        val fastOrder = new CompletableFuture[Int]()

        // Fire slow request first
        client
          .newRequest(s"${server.baseUri}slow")
          .send { _ =>
            val _ = slowOrder.complete(order.incrementAndGet())
          }

        // Fire fast request second
        client
          .newRequest(s"${server.baseUri}fast")
          .send { _ =>
            val _ = fastOrder.complete(order.incrementAndGet())
          }

        val fastPos = fastOrder.get(5, TimeUnit.SECONDS)
        val slowPos = slowOrder.get(5, TimeUnit.SECONDS)

        assertEquals(fastPos, 1, "fast request should complete first")
        assertEquals(slowPos, 2, "slow request should complete second")
      }
    }
  }
}
