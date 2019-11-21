package example

import cats.effect.{ConcurrentEffect, ExitCode, IO, IOApp}
import cats.syntax.functor._
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.{Channel, ChannelHandlerContext, ChannelInboundHandler, ChannelInboundHandlerAdapter}
import io.netty.handler.codec.http.HttpRequest
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.{HttpApp, HttpRoutes}

import scala.util.control.NonFatal

object NettyTestServer extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    val app = HttpRoutes
      .of[IO] {
        case GET -> Root / "hello" => Ok("Hello World")
      }
      .orNotFound

    IO.async { (cb: Either[Throwable, Channel] => Unit) =>
        val server = EchoServer.start(onChannelRead(app, err => cb(Left(err))))
        cb(Right(server.bind(8081).channel()))
      }
      .as(ExitCode.Success)
  }

  def onChannelRead[F[_]](app: HttpApp[F], onError: (Throwable) => Unit)(implicit F: ConcurrentEffect[F]): ChannelInboundHandler =
    new AutoReadHandler {
      override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit =
        msg match {
          case req: HttpRequest =>
            Translate.toHttp4sRequest[F](req) match {
              case Left(error) =>
                onError(error)
              case Right(request) =>
                try {
                  F.runAsync(app(request)) {
                      case Left(error) =>
                        IO(onError(error))
                      case Right(response) =>
                        IO {
                          ctx.write(Translate.toNettyResponse(response))
                          ()
                        }
                    }
                    .unsafeRunSync()
                } catch {
                  case NonFatal(e) => onError(e)
                }
            }
        }
    }

  @Sharable
  class AutoReadHandler extends ChannelInboundHandlerAdapter {
    override def channelActive(ctx: ChannelHandlerContext): Unit = {
      ctx.read()
      ()
    }
  }
}
