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
import cats.syntax.all._
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

import scala.concurrent.TimeoutException
import scala.concurrent.duration.*

class NettyClientIdleTimeoutTest extends IOSuite {
  override val munitIOTimeout: Duration = 1.minute

  val nettyClient: IOFixture[Client[IO]] =
    resourceFixture(
      NettyClientBuilder[IO]
        .withIdleTimeout(3.seconds)
        .withMaxConnectionsPerKey(2)
        .resource,
      "netty client")

  def respond(path: String, sleep: FiniteDuration, value: String): IO[Response[IO]] =
    IO.println(s"server: received /${path} request, sleeping...") >>
      IO.sleep(sleep) >>
      IO.println(s"server: responding with '$value'") >> Ok(value)

  val server: IOFixture[Server] = resourceFixture(
    EmberServerBuilder
      .default[IO]
      .withPort(port"0")
      .withHttpApp(
        HttpRoutes
          .of[IO] {
            case GET -> Root / "idle-timeout" =>
              respond("idle-timeout", 4.seconds, "Wat")
            case GET -> Root / "1" =>
              respond("1", 5.seconds, "1")
            case GET -> Root / "2" =>
              respond("2", 1.seconds, "2")
          }
          .orNotFound
      )
      .build,
    "server"
  )

  test("fails after idle timeout") {
    val s = server()

    val req = Request[IO](uri = s.baseUri / "idle-timeout")
    val response = nettyClient().status(req).attempt
    IO.race(response, IO.sleep(5.seconds)).map {
      case Left(Left(error: TimeoutException)) =>
        assertEquals(error.getMessage, "Closing connection due to idle timeout")
      case Left(Left(error)) => fail(s"Failed with $error")
      case Left(Right(_)) => fail("response available")
      case Right(_) => fail("idle timeout wasn't triggered")
    }
  }

  test("Request A timed out, request B receives response B") {
    val s = server()
    val c = nettyClient()

    val req1 = Request[IO](uri = s.baseUri / "1")
    val req2 = Request[IO](uri = s.baseUri / "2")
    for {
      _ <- c.expect[String](req1).attempt.map(_.leftMap(_.getMessage))
      r2 <- c.expect[String](req2).attempt.map(_.leftMap(_.getMessage))
    } yield assertEquals(r2, Left("Closing connection due to idle timeout"))
  }
}
