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

import cats.data.Kleisli
import cats.effect.IO
import cats.effect.Resource
import cats.implicits._
import fs2._
import org.http4s.HttpRoutes
import org.http4s.Request
import org.http4s.Response
import org.http4s.client.Client
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.jdkhttpclient.JdkHttpClient
import org.http4s.netty.client.NettyClientBuilder
import org.http4s.server.Server

import java.net.http.HttpClient
import scala.concurrent.duration._

abstract class ServerTest extends IOSuite {

  val server: Fixture[Server] = resourceFixture(
    NettyServerBuilder[IO]
      .withHttpApp(ServerTest.routes)
      .withEventLoopThreads(10)
      .withIdleTimeout(2.seconds)
      .withoutBanner
      .bindAny()
      .resource,
    "server"
  )

  def client: Fixture[Client[IO]]

  test("simple") { /* (server: Server[IO], client: Client[IO]) =>*/
    val uri = server().baseUri / "simple"
    client().expect[String](uri).map(body => assertEquals(body, "simple path"))

  }

  test("no-content") { /*(server: Server[IO], client: Client[IO]) =>*/
    val uri = server().baseUri / "no-content"
    client().statusFromUri(uri).map { status =>
      assertEquals(status, NoContent)
    }
  }

  test("delayed") { /*(server: Server[IO], client: Client[IO]) =>*/
    val uri = server().baseUri / "delayed"

    client().expect[String](uri).map { body =>
      assertEquals(body, "delayed path")
    }
  }

  test("echo") { /*(server: Server[IO], client: Client[IO]) =>*/
    val uri = server().baseUri / "echo"

    client().expect[String](Request[IO](POST, uri).withEntity("hello")).map { body =>
      assertEquals(body, "hello")
    }
  }
  test("chunked") { /*(server: Server[IO], client: Client[IO]) =>*/
    val uri = server().baseUri / "chunked"

    client().run(Request[IO](POST, uri).withEntity("hello")).use { res =>
      res.as[String].map { body =>
        assert(res.isChunked)
        assertEquals(res.status, Ok)
        assertEquals(body, "hello")
      }
    }
  }
  test("timeout") { /*(server: Server[IO], client: Client[IO]) =>*/
    val uri = server().baseUri / "timeout"
    client().expect[String](uri).timeout(5.seconds).attempt.map(e => assert(e.isLeft))
  }
}

class JDKServerTest extends ServerTest {
  val client: Fixture[Client[IO]] =
    resourceFixture(Resource.pure(JdkHttpClient[IO](HttpClient.newHttpClient())), "client")
}

class NettyClientServerTest extends ServerTest {
  val client: Fixture[Client[IO]] = resourceFixture(
    NettyClientBuilder[IO]
      .withEventLoopThreads(2)
      .resource,
    "client"
  )
}

object ServerTest {
  def routes: Kleisli[IO, Request[IO], Response[IO]] =
    HttpRoutes
      .of[IO] {
        case GET -> Root / "simple" => Ok("simple path")
        case req @ POST -> Root / "chunked" =>
          Response[IO](Ok)
            .withEntity(Stream.eval(req.as[String]))
            .pure[IO]
        case GET -> Root / "timeout" => IO.never
        case GET -> Root / "delayed" =>
          IO.sleep(1.second) *>
            Ok("delayed path")
        case GET -> Root / "no-content" => NoContent()
        case r @ POST -> Root / "echo" =>
          Ok(r.as[String])
        case GET -> Root / "not-found" => NotFound("not found")
        case GET -> Root / "empty-not-found" => NotFound()
        case GET -> Root / "internal-error" => InternalServerError()
      }
      .orNotFound
}
