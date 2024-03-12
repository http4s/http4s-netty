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
import com.comcast.ip4s._
import munit.catseffect.IOFixture
import org.http4s.HttpRoutes
import org.http4s.ProductId
import org.http4s.Request
import org.http4s.Response
import org.http4s.client.Client
import org.http4s.dsl.io._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.`User-Agent`
import org.http4s.implicits._
import org.http4s.server.Server

import scala.concurrent.duration._

class EchoHeaderTest extends IOSuite {
  private val defaultUserAgent: `User-Agent` = `User-Agent`(ProductId("http4s-client", Some("1.0")))
  val client: IOFixture[Client[IO]] =
    resourceFixture(
      NettyClientBuilder[IO]
        .withIdleTimeout(10.seconds)
        .withUserAgent(defaultUserAgent)
        .resource,
      "client")

  val server: IOFixture[Server] = resourceFixture(
    EmberServerBuilder
      .default[IO]
      .withPort(port"0")
      .withHttpApp(
        HttpRoutes
          .of[IO] { case r @ GET -> Root / "echo-ua" =>
            val ua = r.headers.get[`User-Agent`]
            ua match {
              case Some(value) => IO(Response[IO]().putHeaders(value))
              case None => NotFound()
            }
          }
          .orNotFound
      )
      .build,
    "server"
  )

  test("echo useragent back") {
    val s = server()

    client().get(s.baseUri / "echo-ua")(res =>
      res.headers.get[`User-Agent`] match {
        case Some(value) => IO(assertEquals(value, defaultUserAgent))
        case None => IO(fail("No user-agent header found"))
      })
  }

  test("echo useragent back override") {
    val s = server()

    val overrideUA = `User-Agent`(ProductId("override"))
    client()
      .run(Request[IO](uri = s.baseUri / "echo-ua").putHeaders(overrideUA))
      .use(res =>
        res.headers.get[`User-Agent`] match {
          case Some(value) => IO(assertEquals(value, overrideUA))
          case None => IO(fail("No user-agent header found"))
        })
  }
}
