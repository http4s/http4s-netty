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

import cats.effect.IO
import org.http4s.HttpVersion
import org.http4s.Request
import org.http4s.implicits._
import munit.catseffect.IOFixture
import org.http4s.client.Client

class NettyHttp3ClientTest extends IOSuite {

  val client: IOFixture[Client[IO]] = resourceFixture(NettyHttp3ClientBuilder[IO].resource, "client")

  test("google") {
    client()
      .expect[String](
        Request[IO](uri = uri"https://www.google.com/", httpVersion = HttpVersion.`HTTP/3`))
      .flatMap(s => IO.println(s) >> IO.delay(assertEquals(s, "")))
    // .assertEquals(Status.Ok)
  }
}
