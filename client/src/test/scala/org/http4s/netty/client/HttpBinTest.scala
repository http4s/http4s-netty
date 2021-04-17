package org.http4s.netty.client

import java.net.URI
import cats.syntax.all._
import cats.effect.IO
import cats.effect.kernel.Resource
import org.gaul.httpbin.HttpBin
import org.http4s._

class HttpBinTest extends IOSuite {
  val httpBin = resourceFixture(
    Resource(IO {
      val bin = new HttpBin(URI.create("http://localhost:0"))
      bin.start()
      bin -> IO(bin.stop())
    }),
    "httpbin")

  val client = resourceFixture(NettyClientBuilder[IO].resource, "client")

  test("status 200") {
    val base = Uri.unsafeFromString(s"http://localhost:${httpBin().getPort}")
    client().statusFromUri(base / "status" / "200").map(s => assertEquals(s, Status.Ok))
  }

  test("http GET 10 times") {
    val base = Uri.unsafeFromString(s"http://localhost:${httpBin().getPort}")
    val r =
      for (_ <- 0 until 10)
        yield client().expect[String](base / "get").map(s => assert(s.nonEmpty))
    r.toList.sequence
  }

  test("http POST") {
    val baseUri = Uri.unsafeFromString(s"http://localhost:${httpBin().getPort}")

    client()
      .status(Request[IO](Method.POST, baseUri / "post"))
      .map(s => assertEquals(s, Status.Ok)) *>
      client()
        .status(Request[IO](Method.POST, baseUri / "status" / "204"))
        .map(s => assertEquals(s, Status.NoContent))
  }
}
