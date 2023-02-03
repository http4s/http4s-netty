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
import cats.effect.Deferred
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
      .withNioTransport
      .withIdleTimeout(2.seconds)
      .withoutBanner
      .bindAny()
      .resource,
    "server"
  )

  def client: Fixture[Client[IO]]

  test("simple") {
    val uri = server().baseUri / "simple"
    client().expect[String](uri).map(body => assertEquals(body, "simple path"))

  }

  test("no-content") {
    val uri = server().baseUri / "no-content"
    client().statusFromUri(uri).map { status =>
      assertEquals(status, NoContent)
    }
  }

  test("delayed") {
    val uri = server().baseUri / "delayed"

    client().expect[String](uri).map { body =>
      assertEquals(body, "delayed path")
    }
  }

  test("echo") {
    val uri = server().baseUri / "echo"

    client().expect[String](Request[IO](POST, uri).withEntity("hello")).map { body =>
      assertEquals(body, "hello")
    }
  }
  test("chunked") {
    val uri = server().baseUri / "chunked"

    client().run(Request[IO](POST, uri).withEntity("hello")).use { res =>
      res.as[String].map { body =>
        assert(res.isChunked)
        assertEquals(res.status, Ok)
        assertEquals(body, "hello")
      }
    }
  }
  test("default error handler results in 500 response") {
    val uri = server().baseUri / "boom"
    client().statusFromUri(uri).map { status =>
      assertEquals(status, InternalServerError)
    }
  }

  test("Unhandled service exceptions will be turned into a 500 response") {
    val server: Resource[IO, Server] = NettyServerBuilder[IO]
      .withHttpApp(ServerTest.routes)
      .withServiceErrorHandler(_ => PartialFunction.empty)
      .withEventLoopThreads(1)
      .withIdleTimeout(2.seconds)
      .withoutBanner
      .bindAny()
      .resource

    server.use { server =>
      val uri = server.baseUri / "boom"
      client().statusFromUri(uri).map { status =>
        assertEquals(status, InternalServerError)
      }
    }
  }

  test("requests can be cancelled") {
    val ref: Deferred[IO, Boolean] = Deferred.unsafe[IO, Boolean]
    val route = HttpRoutes
      .of[IO] { case GET -> Root / "cancel" =>
        (IO.never *> IO.defer(Ok(""))).onCancel(ref.complete(true).void)
      }
      .orNotFound

    val server: Resource[IO, Server] = NettyServerBuilder[IO]
      .withHttpApp(route)
      .withEventLoopThreads(1)
      .withIdleTimeout(
        1.seconds
      ) // Basically going to send the request and hope it times out immediately.
      .withoutBanner
      .bindAny()
      .resource

    server.use { server =>
      val uri = server.baseUri / "cancel"
      val resp = client().statusFromUri(uri).timeout(15.seconds).attempt.map { case other =>
        fail(s"unexpectedly received a result: $other")
      }

      IO.race(resp, ref.get.map(assert(_)))
        .map(_.merge)
    }
  }

  test("timeout") {
    val uri = server().baseUri / "timeout"
    client().expect[String](uri).attempt.map(e => assert(e.isLeft))
  }

  test("H2 Prior Knowledge is supported") {
    // We need to specifically use the Jetty client and configure
    // prior knowledge support. Unfortunately the other clients already
    // on the classpath don't support prior-knowledge.

    import org.eclipse.jetty.http2.client.HTTP2Client
    import org.eclipse.jetty.http2.client.http.HttpClientTransportOverHTTP2
    import org.eclipse.jetty.client.HttpClient

    val server: Resource[IO, Server] = NettyServerBuilder[IO]
      .withHttpApp(ServerTest.routes)
      .withEventLoopThreads(1)
      .withoutBanner
      .bindAny()
      .resource

    server.use { server =>
      IO.delay {
        val http2 = new HttpClientTransportOverHTTP2(new HTTP2Client())
        http2.setUseALPN(false)

        val client = new HttpClient(http2)
        client.start()
        try {
          val resp = client.GET(s"${server.baseUri}simple")
          assert(resp.getStatus == 200)
          assert(resp.getContentAsString == "simple path")
        } finally
          client.stop()
      }
    }
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
        case GET -> Root / "boom" => IO.raiseError(new Exception("so sad"))
      }
      .orNotFound
}
