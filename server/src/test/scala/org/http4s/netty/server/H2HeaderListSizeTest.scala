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

import cats.effect.IO
import cats.effect.Resource
import munit.CatsEffectSuite
import org.http4s.HttpRoutes
import org.http4s.dsl.io._
import org.http4s.server.Server
import org.typelevel.ci._

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import scala.concurrent.duration._

class H2HeaderListSizeTest extends CatsEffectSuite {

  private val largeHeaderValue = "a" * (16 * 1024)

  private def serverResource: Resource[IO, Server] = {
    val routes = HttpRoutes
      .of[IO] { case req @ GET -> Root / "header" =>
        Ok(req.headers.get(ci"x-large").map(_.head.value.length).getOrElse(0).toString)
      }
      .orNotFound

    NettyServerBuilder[IO]
      .withHttpApp(routes)
      .withEventLoopThreads(1)
      .withShutdownTimeout(1.second)
      .withMaxHeaderSize(32 * 1024)
      .withoutBanner
      .bindAny()
      .resource
  }

  test("H2C upgrade: headers up to maxHeaderSize are accepted on the upgraded connection") {
    serverResource.use { server =>
      IO.blocking {
        val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build()
        val request = HttpRequest
          .newBuilder(URI.create(s"${server.baseUri}header"))
          .header("x-large", largeHeaderValue)
          .timeout(java.time.Duration.ofSeconds(5))
          .GET()
          .build()
        // The first request performs the h2c upgrade, the second one is sent as HTTP/2 HEADERS.
        List.fill(2)(client.send(request, HttpResponse.BodyHandlers.ofString()))
      }.map { responses =>
        assertEquals(responses.map(_.version), List.fill(2)(HttpClient.Version.HTTP_2))
        assertEquals(responses.map(_.statusCode), List(200, 200))
        assertEquals(responses.map(_.body), List.fill(2)(largeHeaderValue.length.toString))
      }
    }
  }
}
