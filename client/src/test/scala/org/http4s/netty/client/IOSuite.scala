/*
 * Copyright 2020 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s.netty.client

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
