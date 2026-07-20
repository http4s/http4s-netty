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
import cats.effect.Resource
import munit.CatsEffectSuite
import org.http4s.HttpRoutes
import org.http4s.dsl.io._
import org.http4s.server.Server
import org.typelevel.ci._

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import scala.concurrent.duration._

class H2UpgradeTest extends CatsEffectSuite {

  test("H2 upgrade from HTTP/1.1 is supported") {
    serverResource(ServerTest.routes).use { server =>
      sendUpgradeRequest(server, "/simple").map { statusLine =>
        assert(
          statusLine.contains("101"),
          s"Expected 101 Switching Protocols but got: $statusLine"
        )
      }
    }
  }

  test("H2 upgrade request is processed on an HTTP/2 stream") {
    Deferred[IO, Boolean].flatMap { wasH2Stream =>
      val routes = HttpRoutes
        .of[IO] { case req @ GET -> Root / "protocol" =>
          val isH2 = req.headers.get(ci"x-http2-stream-id").isDefined
          wasH2Stream.complete(isH2) *> Ok("ok")
        }
        .orNotFound
      serverResource(routes).use { server =>
        val addr = server.address
        Resource
          .fromAutoCloseable(IO.blocking(new Socket(addr.getHostName, addr.getPort)))
          .use { socket =>
            IO.blocking {
              socket.setSoTimeout(5000)
              val out = socket.getOutputStream
              val writer = new PrintWriter(out, true)
              writer.print(
                s"GET /protocol HTTP/1.1\r\n" +
                  s"Host: ${addr.getHostName}:${addr.getPort}\r\n" +
                  "Connection: Upgrade, HTTP2-Settings\r\n" +
                  "Upgrade: h2c\r\n" +
                  "HTTP2-Settings: AAMAAABkAAQBAAAAAAIAAAAA\r\n" +
                  "\r\n"
              )
              writer.flush()

              val reader = new BufferedReader(new InputStreamReader(socket.getInputStream))
              val statusLine = reader.readLine()
              assert(statusLine.contains("101"), s"Expected 101 but got: $statusLine")

              // Read remaining 101 headers
              var line = reader.readLine()
              while (line != null && line.nonEmpty) line = reader.readLine()

              // Send HTTP/2 client connection preface + empty SETTINGS
              out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes("US-ASCII"))
              out.write(Array[Byte](0, 0, 0, 0x04, 0, 0, 0, 0, 0))
              out.flush()
            } *> wasH2Stream.get.map(assert(_, "Expected request on an HTTP/2 stream"))
          }
      }
    }
  }

  private def serverResource(app: org.http4s.HttpApp[IO]): Resource[IO, Server] =
    NettyServerBuilder[IO]
      .withHttpApp(app)
      .withEventLoopThreads(1)
      .withShutdownTimeout(1.second)
      .withoutBanner
      .bindAny()
      .resource

  private def sendUpgradeRequest(server: Server, path: String): IO[String] =
    IO.blocking {
      val addr = server.address
      val socket = new Socket(addr.getHostName, addr.getPort)
      try {
        socket.setSoTimeout(5000)
        val writer = new PrintWriter(socket.getOutputStream, true)
        writer.print(
          s"GET $path HTTP/1.1\r\n" +
            s"Host: ${addr.getHostName}:${addr.getPort}\r\n" +
            "Connection: Upgrade, HTTP2-Settings\r\n" +
            "Upgrade: h2c\r\n" +
            "HTTP2-Settings: AAMAAABkAAQBAAAAAAIAAAAA\r\n" +
            "\r\n"
        )
        writer.flush()
        val reader = new BufferedReader(new InputStreamReader(socket.getInputStream))
        reader.readLine()
      } finally
        socket.close()
    }
}
