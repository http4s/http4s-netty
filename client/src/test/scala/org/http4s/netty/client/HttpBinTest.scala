package org.http4s.netty.client

import java.net.URI
import cats.implicits._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.gaul.httpbin.HttpBin
import org.http4s.{Method, Request, Status, Uri}
import org.http4s.client.Client

class HttpBinTest extends munit.FunSuite {
  FunFixture[HttpBin](
    _ => {
      val bin = new HttpBin(URI.create("http:localhost:0"))
      bin.start()
      bin
    },
    _.stop())

  override def munitValueTransforms: List[ValueTransform] =
    new ValueTransform(
      "IO",
      { case io: IO[_] => io.unsafeToFuture() }) :: super.munitValueTransforms

  def withClient[A](theTest: (Client[IO]) => IO[A]) = {
    val builder = NettyClientBuilder[IO].resource
    builder.use(theTest)
  }

  test("http GET 10 times") { bin: HttpBin =>
    val base = Uri.unsafeFromString(s"http://localhost:${bin.getPort}")
    withClient { client =>
      val r =
        for (_ <- 0 until 10)
          yield client.expect[String](base / "get").map(s => assert(s.nonEmpty))
      r.toList.sequence
    }
  }

  test("http POST") { bin: HttpBin =>
    val baseUri = Uri.unsafeFromString(s"http://localhost:${bin.getPort}")

    withClient { client =>
      client
        .status(Request[IO](Method.POST, baseUri / "status" / "204"))
        .map(s => assertEquals(s, Status.NoContent))
    }
  }
}
