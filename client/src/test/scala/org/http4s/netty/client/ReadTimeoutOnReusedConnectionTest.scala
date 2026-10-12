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

import scala.concurrent.TimeoutException
import scala.concurrent.duration._

class ReadTimeoutOnReusedConnectionTest extends IOSuite {

  // 6-second read timeout
  val client: IOFixture[Client[IO]] =
    resourceFixture(
      NettyClientBuilder[IO]
        .withReadTimeout(6.seconds)
        .resource,
      "netty client"
    )

  val server: IOFixture[Server] = resourceFixture(
    EmberServerBuilder
      .default[IO]
      .withPort(port"0")
      .withHttpApp(
        HttpRoutes
          .of[IO] {
            case GET -> Root / "fast" => Ok("fast")
            case GET -> Root / "slow" => IO.sleep(3.seconds) >> Ok("slow")
          }
          .orNotFound
      )
      .build,
    "server"
  )

  test("reused connection should get full read timeout, not remainder from previous use") {
    val s = server()
    val c = client()

    for {
      // Request A: fast response, establishes and uses the connection.
      _ <- c.expect[String](Request[IO](uri = s.baseUri / "fast"))
      // Connection returns to pool. The read timeout handler is removed
      // from the pipeline on release. Sleep 5 seconds to simulate pool idle time.
      _ <- IO.sleep(5.seconds)
      // Request B: server takes 3 seconds to respond.
      // 3s is well within the 6s readTimeout, so this SHOULD succeed.
      // A fresh read timeout handler is added on acquire.
      result <- c.expect[String](Request[IO](uri = s.baseUri / "slow")).attempt
    } yield result match {
      case Right(body) =>
        assertEquals(body, "slow")
      case Left(_: TimeoutException) =>
        fail(
          "Read timeout on reused connection fired early — " +
            "the read timeout handler was not added fresh on acquire")
      case Left(e) =>
        fail(s"Unexpected error: $e")
    }
  }
}
