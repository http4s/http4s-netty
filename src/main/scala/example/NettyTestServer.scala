package example

import cats.effect.{ExitCode, IO, IOApp}
//import cats.syntax.functor._
import org.http4s.HttpRoutes
import org.http4s.dsl.io._
import org.http4s.implicits._

object NettyTestServer extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    val app = HttpRoutes
      .of[IO] {
        case GET -> Root / "hello" => Ok("Hello World")
      }
      .orNotFound

    /*IO {
      val server = EchoServer.start(new Http4sHandler(app))
      Right(server.bind(8081).channel())
    }.as(ExitCode.Success)*/

    NettyBuilder[IO].withHttpApp(app).resource.use(_ => IO.never)
  }
}
