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

import cats.effect.Deferred
import cats.effect.IO
import cats.implicits._
import org.http4s.HttpRoutes
import org.http4s.Request
import org.http4s.Response
import org.http4s.client.Client
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.jdkhttpclient.JdkHttpClient
import org.http4s.server.Server

import java.net.http.HttpClient
import scala.concurrent.duration._

class DrainResponseTest extends IOSuite {
  val ref: Deferred[IO,Boolean] = Deferred.unsafe[IO, Boolean]
  val server: Fixture[Server] = resourceFixture(
    NettyServerBuilder[IO]
      .withHttpApp(
        HttpRoutes
          .of[IO] { case GET -> Root =>
            Response[IO](Ok)
              .withEntity(
                fs2.Stream
                  .emit("1")
                  .repeat
                  .covary[IO]
                  .take(1000)
                  .onFinalizeWeak[IO](ref.complete(true).void))
              .pure[IO]
          }
          .orNotFound)
      .withoutBanner
      .bindAny()
      .resource,
    "server"
  )

  val client: Fixture[Client[IO]] = resourceFixture(JdkHttpClient[IO](HttpClient.newHttpClient()), "client")

  test("drain") {
    val uri = server().baseUri
    client().run(Request[IO](uri = uri)).use { res =>
      IO {
        assertEquals(res.status, Ok)
      } *> IO
        .race(
          IO.sleep(3.seconds).map(_ => fail("Unable to run the body before timeout")),
          ref.get.map(assert(_)))
        .map(_.merge)
    }
  }
}
