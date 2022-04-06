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

import java.net.http.HttpClient

import org.http4s.implicits._
import cats.implicits._
import cats.effect.IO
import cats.effect.concurrent.Deferred
import org.http4s.{HttpRoutes, Request, Response}
import org.http4s.jdkhttpclient.JdkHttpClient
import org.http4s.dsl.io._

import scala.concurrent.duration._

class DrainResponseTest extends IOSuite {
  val ref = Deferred.unsafe[IO, Boolean]
  val server = resourceFixture(
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
                  .onFinalizeWeak[IO](ref.complete(true)))
              .pure[IO]
          }
          .orNotFound)
      .withExecutionContext(munitExecutionContext)
      .withoutBanner
      .bindAny()
      .resource,
    "server"
  )

  val client = JdkHttpClient[IO](HttpClient.newHttpClient())

  test("drain") {
    val uri = server().baseUri
    client.run(Request[IO](uri = uri)).use { res =>
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
