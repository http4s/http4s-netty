package net.hamnaberg.http4s.netty

import cats.effect.{ExitCode, IO, IOApp}
import org.http4s.HttpRoutes
import org.http4s.dsl.io._
import org.http4s.implicits._

object NettyTestServer extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    val app = HttpRoutes
      .of[IO] {
        case GET -> Root / "hello" => Ok("Hello World in " + Thread.currentThread().getName)
      }
      .orNotFound

    NettyServerBuilder[IO].withHttpApp(app).resource.use(_ => IO.never)
  }
}
