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
import org.http4s.Response
import org.http4s.Status
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.server.Server

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import scala.concurrent.duration._

class InvalidRequestLineTest extends CatsEffectSuite {

  private val app = HttpRoutes
    .of[IO] { case GET -> Root / "ok" =>
      Ok("ok")
    }
    .orNotFound

  private def serverResource: Resource[IO, Server] =
    NettyServerBuilder[IO]
      .withHttpApp(app)
      .withNioTransport
      .withIdleTimeout(5.seconds)
      .withoutBanner
      .bindAny()
      .resource

  private def sendRawRequest(server: Server, requestLine: String): IO[String] =
    IO.blocking {
      val addr = server.address
      val socket = new Socket(addr.getHostName, addr.getPort)
      try {
        socket.setSoTimeout(5000)
        val writer = new PrintWriter(socket.getOutputStream, true)
        writer.print(s"$requestLine\r\nHost: localhost\r\n\r\n")
        writer.flush()
        val reader = new BufferedReader(new InputStreamReader(socket.getInputStream))
        reader.readLine() // e.g. "HTTP/1.1 400 Bad Request"
      } finally
        socket.close()
    }

  test("invalid URI returns 400 by default") {
    serverResource.use { server =>
      sendRawRequest(server, "GET /hello?foo={ HTTP/1.1").map { statusLine =>
        assert(statusLine.contains("400"), s"Expected 400 but got: $statusLine")
      }
    }
  }

  test("configurable requestLineParseErrorHandler") {
    val customServer = NettyServerBuilder[IO]
      .withHttpApp(app)
      .withNioTransport
      .withIdleTimeout(5.seconds)
      .withoutBanner
      .withRequestLineParseErrorHandler(_ => IO.pure(Response[IO](Status.NotFound)))
      .bindAny()
      .resource

    customServer.use { server =>
      sendRawRequest(server, "GET /hello?foo={ HTTP/1.1").map { statusLine =>
        assert(statusLine.contains("404"), s"Expected 404 but got: $statusLine")
      }
    }
  }
}
