package org.http4s.client.netty

import cats.effect.{ContextShift, IO, Timer}
import org.http4s.client.netty.NettyTransport.Native

class ExampleTest extends munit.FunSuite {
  implicit val context: ContextShift[IO] = IO.contextShift(munitExecutionContext)
  implicit val timer: Timer[IO] = IO.timer(munitExecutionContext)

  override def munitValueTransforms: List[ValueTransform] =
    new ValueTransform(
      "IO",
      { case io: IO[_] => io.unsafeToFuture() }) :: super.munitValueTransforms

  test("Get example.com") {
    val builder = new NettyClientBuilder[IO](0, 1024, 8092, 4096, Native, munitExecutionContext)
    builder.build().use(_.expect[String]("http://example.com"))
  }

}
