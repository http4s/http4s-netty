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

package org.http4s.netty.client

import cats.effect.IO
import cats.effect.kernel.Deferred
import com.comcast.ip4s._
import fs2.Stream
import munit.catseffect.IOFixture
import org.http4s.HttpRoutes
import org.http4s.Request
import org.http4s.client.Client
import org.http4s.dsl.io._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
import org.http4s.server.Server

import scala.concurrent.duration._

class NettyClientStreamCancelTest extends IOSuite {

  val client: IOFixture[Client[IO]] =
    resourceFixture(
      NettyClientBuilder[IO]
        .withMaxConnectionsPerKey(1)
        .resource,
      "netty client")

  val server: IOFixture[Server] = resourceFixture(
    EmberServerBuilder
      .default[IO]
      .withPort(port"0")
      .withHttpApp(
        HttpRoutes
          .of[IO] {
            case GET -> Root / "stream" =>
              // Return a continuous chunked stream — will keep sending until cancelled
              val body = Stream
                .fixedDelay[IO](10.millis)
                .zipWithIndex
                .map { case (_, i) => s"chunk-$i\n" }
                .through(fs2.text.utf8.encode)
              Ok(body)
            case GET -> Root / "simple" =>
              Ok("ok")
          }
          .orNotFound
      )
      .build,
    "server"
  )

  test(
    "cancelled streaming response should not corrupt next request on the same pooled connection") {
    val s = server()
    val c = client()

    // Request A: open a streaming response, read a few chunks, then cancel via timeout
    val cancelledRequest = c
      .run(Request[IO](uri = s.baseUri / "stream"))
      .use { resp =>
        // Read body for a short while then let the use block return (cancelling the stream)
        resp.body
          .take(256) // read a limited amount then stop
          .compile
          .drain
      }

    // Request B: should get the correct response, not leftover data from request A
    val followUp = c.expect[String](Request[IO](uri = s.baseUri / "simple"))

    for {
      _ <- cancelledRequest
      result <- followUp
    } yield assertEquals(result, "ok")
  }

  test("explicitly cancelled streaming response should not corrupt next request") {
    val s = server()
    val c = client()

    Deferred[IO, Unit].flatMap { gate =>
      // Request A: open a streaming response and cancel it via IO.race
      val streamingRequest = c
        .run(Request[IO](uri = s.baseUri / "stream"))
        .use { resp =>
          // Signal that we've opened the response
          gate.complete(()) >>
            // Read the body forever (will be cancelled)
            resp.body.compile.drain
        }

      for {
        fiber <- streamingRequest.start
        _ <- gate.get // wait until response is open
        _ <- IO.sleep(100.millis) // let some chunks flow
        _ <- fiber.cancel // cancel mid-stream
        result <- c.expect[String](Request[IO](uri = s.baseUri / "simple"))
      } yield assertEquals(result, "ok")
    }
  }
}
