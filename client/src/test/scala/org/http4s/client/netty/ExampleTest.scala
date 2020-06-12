package org.http4s.client.netty

import cats.effect.{ContextShift, IO, Timer}

class ExampleTest extends munit.FunSuite {
  implicit val context: ContextShift[IO] = IO.contextShift(munitExecutionContext)
  implicit val timer: Timer[IO] = IO.timer(munitExecutionContext)

  override def munitValueTransforms: List[ValueTransform] =
    new ValueTransform(
      "IO",
      { case io: IO[_] => io.unsafeToFuture() }) :: super.munitValueTransforms

  test("Get example.com") {
    val builder = NettyClientBuilder[IO].resource
    builder.use(_.expect[String]("http://example.com")).flatMap(s => IO(println(s)))
  }

}
