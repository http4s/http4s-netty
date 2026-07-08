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
import com.comcast.ip4s._
import munit.catseffect.IOFixture
import org.http4s.HttpRoutes
import org.http4s.Request
import org.http4s.client.Client
import org.http4s.dsl.io._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
import org.http4s.server.Server

import scala.concurrent.duration._

class NettyClientConnectionAgeTest extends IOSuite {

  // Client registered before server so it tears down first (reverse order),
  // ensuring pooled connections are closed before the server shuts down.
  val shortAgeClient: IOFixture[Client[IO]] =
    resourceFixture(
      NettyClientBuilder[IO]
        .withMaxConnectionAge(2.seconds)
        .resource,
      "short-age-client")

  val server: IOFixture[Server] = resourceFixture(
    EmberServerBuilder
      .default[IO]
      .withPort(port"0")
      .withHttpApp(
        HttpRoutes
          .of[IO] {
            case GET -> Root / "fast" => Ok("fast")
            case GET -> Root / "slow" => IO.sleep(2.seconds) >> Ok("slow")
          }
          .orNotFound
      )
      .build,
    "server"
  )

  test("connection is replaced after max age expires") {
    val s = server()
    val c = shortAgeClient()

    val req = Request[IO](uri = s.baseUri / "fast")
    for {
      r1 <- c.expect[String](req)
      _ <- IO.sleep(3.seconds)
      r2 <- c.expect[String](req)
    } yield {
      assertEquals(r1, "fast")
      assertEquals(r2, "fast")
    }
  }

  test("in-flight request completes even if connection age expires during request") {
    val s = server()
    val c = shortAgeClient()

    val req = Request[IO](uri = s.baseUri / "slow")
    for {
      r <- c.expect[String](req)
    } yield assertEquals(r, "slow")
  }
}
