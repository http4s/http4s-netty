package org.http4s.netty.client

import cats.effect.IO
import cats.effect.IOApp
import org.http4s.Request
import org.http4s.syntax.literals._

object TestMain extends IOApp.Simple {
  override def run: IO[Unit] =
    NettyClientBuilder[IO].resource.use { client =>
      client.status(Request[IO](uri = uri"https://www.vg.no/")).flatMap(IO.println)
    }
}
