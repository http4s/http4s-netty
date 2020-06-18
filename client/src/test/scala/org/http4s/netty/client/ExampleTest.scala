package org.http4s.netty.client

import cats.implicits._
import cats.effect.{ContextShift, IO, Timer}

class ExampleTest extends munit.FunSuite {
  implicit val context: ContextShift[IO] = IO.contextShift(munitExecutionContext)
  implicit val timer: Timer[IO] = IO.timer(munitExecutionContext)

  override def munitValueTransforms: List[ValueTransform] =
    new ValueTransform(
      "IO",
      { case io: IO[_] => io.unsafeToFuture() }) :: super.munitValueTransforms

  /*test("Get example.com") {
    val builder = NettyClientBuilder[IO].resource
    builder.use(_.expect[String]("http://example.com")).map(s => assert(s.nonEmpty))
  }*/
  test("TLS Get nrk.no") {
    val builder = NettyClientBuilder[IO].resource
    builder.use { client =>
      val r =
        for (_ <- 0 until 10)
          yield client.expect[String]("https://www.nrk.no").map(s => assert(s.nonEmpty))
      r.toList.sequence
    }
  }

}
