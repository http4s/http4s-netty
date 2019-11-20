package example

import cats.syntax.functor._
import cats.effect.{ExitCode, IO, IOApp}
import io.netty.channel.{Channel, ChannelHandlerContext}
import io.netty.handler.codec.http.HttpRequest
import org.http4s.HttpRoutes
import org.http4s.implicits._
import org.http4s.dsl.io._

object NettyTestServer extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    val app = HttpRoutes
      .of[IO] {
        case GET -> Root / "hello" => Ok("Hello World")
      }
      .orNotFound

    IO.async { cb: ((Either[Throwable, Channel]) => Unit) =>
        val server = EchoServer.start(new EchoServer.AutoReadHandler {
          override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
            msg match {
              case req: HttpRequest =>
                Translate.toHttp4sRequest[IO](req) match {
                  case Left(error) =>
                    cb(Left(error))
                  case Right(request) =>
                    app(request).unsafeRunAsync {
                      case Left(error) =>
                        cb(Left(error))
                      case Right(response) =>
                        ctx.write(Translate.toNettyResponse(response))
                        ()
                    }
                }
            }
            ()
          }
        })

        cb(Right(server.bind(8081).channel()))
      }
      .as(ExitCode.Success)
  }
}
