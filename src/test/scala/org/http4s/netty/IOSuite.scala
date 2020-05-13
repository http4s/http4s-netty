package org.http4s.netty

import cats.effect.{ContextShift, IO, Resource, Timer}

abstract class IOSuite extends munit.FunSuite {
  implicit val context: ContextShift[IO] = IO.contextShift(munitExecutionContext)
  implicit val timer: Timer[IO]          = IO.timer(munitExecutionContext)
  class ResourceFixture[A](resource: Resource[IO, A], name: String) extends Fixture[A](name) {
    private var value: Option[A]  = None
    private var cleanup: IO[Unit] = IO.unit
    def apply()                   = value.get
    override def beforeAll(): Unit = {
      val result = resource.allocated.unsafeRunSync()
      value = Some(result._1)
      cleanup = result._2
    }
    override def afterAll(): Unit = {
      cleanup.unsafeRunSync()
    }
  }
  def resourceFixture[A](resource: Resource[IO, A], name: String): Fixture[A] =
    new ResourceFixture[A](resource, name)

  override def munitValueTransforms: List[ValueTransform] =
    new ValueTransform("IO", { case io: IO[_] => io.unsafeToFuture() }) :: super.munitValueTransforms

}
