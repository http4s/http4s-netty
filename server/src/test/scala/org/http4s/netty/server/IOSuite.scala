package org.http4s.netty.server

import cats.effect.{IO, Resource}
import munit.CatsEffectSuite

abstract class IOSuite extends CatsEffectSuite {

  private val fixtures = List.newBuilder[Fixture[_]]

  def resourceFixture[A](resource: Resource[IO, A], name: String): Fixture[A] = {
    val fixture = ResourceSuiteLocalFixture(name, resource)
    fixtures += fixture
    fixture
  }

  override def munitFixtures: Seq[Fixture[_]] = fixtures.result()

}
