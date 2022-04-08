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

import java.net.URI
import cats.syntax.all._
import cats.effect._

import org.gaul.httpbin.HttpBin
import org.http4s._

class HttpBinTest extends IOSuite {

  val httpBin = resourceFixture(HttpBinTest.httpBin, "httpbin")

  val client = resourceFixture(NettyClientBuilder[IO].resource, "client")

  test("status 200") {
    val base = httpBin()
    client().statusFromUri(base / "status" / "200").map(s => assertEquals(s, Status.Ok))
  }

  test("http GET 10 times") {
    val base = httpBin()
    val r =
      for (_ <- 0 until 10)
        yield client().expect[String](base / "get").map(s => assert(s.nonEmpty))
    r.toList.sequence
  }

  test("http POST") {
    val baseUri = httpBin()

    client()
      .status(Request[IO](Method.POST, baseUri / "post"))
      .map(s => assertEquals(s, Status.Ok)) *>
      client()
        .status(Request[IO](Method.POST, baseUri / "status" / "204"))
        .map(s => assertEquals(s, Status.NoContent))
  }
}

object HttpBinTest {

  private def uriFrom(bin: HttpBin) = Uri.unsafeFromString(s"http://localhost:${bin.getPort}")

  def httpBin = Resource(IO {
    val bin = new HttpBin(URI.create("http://localhost:0"))
    bin.start()
    uriFrom(bin) -> IO(bin.stop())
  })
}
