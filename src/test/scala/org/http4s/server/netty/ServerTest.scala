package org.http4s.server.netty

import java.net.http.HttpClient

import cats.implicits._
import cats.effect.{IO, Timer}
import org.http4s.{HttpRoutes, Request, Response}
import org.http4s.implicits._
import org.http4s.dsl.io._
import fs2._
import org.http4s.client.jdkhttpclient.JdkHttpClient

import scala.concurrent.duration._

class ServerTest extends IOSuite {

  val server = resourceFixture(
    NettyServerBuilder[IO]
      .withHttpApp(ServerTest.routes)
      .withIdleTimeout(2.seconds)
      .withExecutionContext(munitExecutionContext)
      .withoutBanner
      .bindAny()
      .resource,
    "server"
  )
  val client = JdkHttpClient[IO](HttpClient.newHttpClient())

  test("simple") {
    val uri = server().baseUri / "simple"
    client.expect[String](uri).map(body => assertEquals(body, "simple path"))
  }

  test("no-content") {
    val uri = server().baseUri / "no-content"
    client.statusFromUri(uri).map { status =>
      assertEquals(status, NoContent)
    }
  }

  test("delayed") {
    val uri = server().baseUri / "delayed"

    client.expect[String](uri).map { body =>
      assertEquals(body, "delayed path")
    }
  }
  test("chunked") {
    val uri = server().baseUri / "chunked"

    client.run(Request[IO](POST, uri).withEntity("hello")).use { res =>
      res.as[String].map { body =>
        assert(res.isChunked)
        assertEquals(res.status, Ok)
        assertEquals(body, "hello")
      }
    }
  }
  test("timeout") {
    val uri = server().baseUri / "timeout"
    client.expect[String](uri).timeout(5.seconds).attempt.map(e => assert(e.isLeft))
  }
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
        case GET -> Root / "not-found" => NotFound("not found")
        case GET -> Root / "empty-not-found" => NotFound()
        case GET -> Root / "internal-error" => InternalServerError()
      }
      .orNotFound
}
