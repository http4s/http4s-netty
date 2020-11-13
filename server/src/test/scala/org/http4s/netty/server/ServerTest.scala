package org.http4s.netty.server

import java.net.http.HttpClient

import cats.implicits._
import cats.effect.{IO, Resource, Timer}
import org.http4s.{HttpRoutes, Request, Response}
import org.http4s.implicits._
import org.http4s.dsl.io._
import fs2._
import org.http4s.client.Client
import org.http4s.client.jdkhttpclient.JdkHttpClient
import org.http4s.netty.client.NettyClientBuilder
//import org.http4s.server.Server

import scala.concurrent.duration._

abstract class ServerTest extends IOSuite {

  val server = resourceFixture(
    NettyServerBuilder[IO]
      .withHttpApp(ServerTest.routes)
      .withEventLoopThreads(10)
      .withIdleTimeout(2.seconds)
      .withExecutionContext(munitExecutionContext)
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
  val client = resourceFixture(
    Resource.pure[IO, Client[IO]](JdkHttpClient[IO](HttpClient.newHttpClient())),
    "client")
}

class NettyClientServerTest extends ServerTest {
  val client = resourceFixture(
    NettyClientBuilder[IO].resource,
    "client"
  )
}

object ServerTest {
  def routes(implicit timer: Timer[IO]) =
    HttpRoutes
      .of[IO] {
        case req @ _ -> Root / "echo" => Ok(req.as[String])
        case GET -> Root / "simple" => Ok("simple path")
        case req @ POST -> Root / "chunked" =>
          Response[IO](Ok)
            .withEntity(Stream.eval(req.as[String]))
            .pure[IO]
        case GET -> Root / "timeout" => IO.never
        case GET -> Root / "delayed" =>
          timer.sleep(1.second) *>
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
