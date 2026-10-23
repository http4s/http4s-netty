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

/** Reproduces IllegalStateException: void future caused by safedispatch using channel.voidPromise
  * which is incompatible with HttpStreamsHandler.unbufferedWrite adding a listener to the promise.
  *
  * When IdleStateHandler is present (finite idle timeout), it calls promise.unvoid() before
  * forwarding to HttpStreamsHandler. With Duration.Inf, no IdleStateHandler is installed, so the
  * void promise reaches HttpStreamsHandler directly and crashes.
  */
class VoidPromiseTest extends IOSuite {

  val server: IOFixture[Server] = resourceFixture(
    EmberServerBuilder
      .default[IO]
      .withPort(port"0")
      .withHttpApp(
        HttpRoutes
          .of[IO] { case GET -> Root / "hello" =>
            Ok("world")
          }
          .orNotFound
      )
      .build,
    "server"
  )

  val clientNoIdleTimeout: IOFixture[Client[IO]] =
    resourceFixture(
      NettyClientBuilder[IO]
        .withIdleTimeout(Duration.Inf)
        .withMaxConnectionAge(1.second)
        .resource,
      "client-no-idle"
    )

  val clientNoTimeouts: IOFixture[Client[IO]] =
    resourceFixture(
      NettyClientBuilder[IO]
        .withIdleTimeout(Duration.Inf)
        .withReadTimeout(Duration.Inf)
        .withMaxConnectionAge(1.second)
        .resource,
      "client-no-timeouts"
    )

  test("GET request succeeds with idle timeout disabled") {
    val s = server()
    val c = clientNoIdleTimeout()
    c.expect[String](Request[IO](uri = s.baseUri / "hello"))
      .map(body => assertEquals(body, "world"))
  }

  test("GET request succeeds with both idle and read timeout disabled") {
    val s = server()
    val c = clientNoTimeouts()
    c.expect[String](Request[IO](uri = s.baseUri / "hello"))
      .map(body => assertEquals(body, "world"))
  }
}
