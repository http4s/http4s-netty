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

package org.http4s.netty.server

import java.net.URI
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest}

import cats.effect.IO
import org.http4s.Uri

import scala.concurrent.duration._
import org.scalacheck.Prop._

class TestServerPropTest extends IOSuite with munit.ScalaCheckSuite {

  val server = resourceFixture(
    NettyServerBuilder[IO]
      .withHttpApp(ServerTest.routes)
      .withIdleTimeout(2.seconds)
      .withEventLoopThreads(10)
      .withExecutionContext(munitExecutionContext)
      .withoutBanner
      .bindAny()
      .resource,
    "server"
  )
  val client = HttpClient.newHttpClient()

  property("POST chunked body") {
    forAll { (body: String) =>
      val s = server()
      val url = s.baseUri / "chunked"
      assertEquals(
        client
          .send(
            HttpRequest.newBuilder(url.toURI).POST(BodyPublishers.ofString(body)).build(),
            BodyHandlers.ofString()
          )
          .body(),
        body
      )
    }
  }

  property("POST normal body") {
    forAll { (body: String) =>
      val s = server()
      val url = s.baseUri / "echo"
      assertEquals(
        client
          .send(
            HttpRequest.newBuilder(url.toURI).POST(BodyPublishers.ofString(body)).build(),
            BodyHandlers.ofString()
          )
          .body(),
        body
      )
    }
  }

  implicit class ToURI(uri: Uri) {
    def toURI = URI.create(uri.renderString)
  }
}
