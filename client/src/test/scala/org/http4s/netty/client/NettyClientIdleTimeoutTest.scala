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
import org.http4s.Response
import org.http4s.client.Client
import org.http4s.dsl.io._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
import org.http4s.server.Server

import scala.concurrent.duration._

class NettyClientIdleTimeoutTest extends IOSuite {
  override val munitIOTimeout: Duration = 1.minute

  val nettyClient: IOFixture[Client[IO]] =
    resourceFixture(
      NettyClientBuilder[IO]
        .withIdleTimeout(2.seconds)
        .resource,
      "netty client")

  val server: IOFixture[Server] = resourceFixture(
    EmberServerBuilder
      .default[IO]
      .withPort(port"0")
      .withHttpApp(
        HttpRoutes
          .of[IO] { case GET -> Root / "idle-timeout" =>
            IO.sleep(30.seconds).as(Response())
          }
          .orNotFound
      )
      .build,
    "server"
  )

  List(
    (nettyClient, "netty client")
  ).foreach { case (client, name) =>
    test(s"$name fails after idle timeout") {
      val s = server()

      val req = Request[IO](uri = s.baseUri / "idle-timeout")
      val response = client().run(req).allocated.attempt
      IO.race(response, IO.sleep(5.seconds)).map {
        case Left(Left(error)) => println(s"response failed, error:"); error.printStackTrace()
        case Left(Right(_)) => println("response available")
        case Right(_) => fail("idle timeout wasn't triggered")
      }
    }
  }
}
