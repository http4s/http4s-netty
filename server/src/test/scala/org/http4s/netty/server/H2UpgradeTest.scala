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
import org.http4s.server.Server

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import scala.concurrent.duration._

class H2UpgradeTest extends CatsEffectSuite {

  private val serverResource: Resource[IO, Server] =
    NettyServerBuilder[IO]
      .withHttpApp(ServerTest.routes)
      .withEventLoopThreads(1)
      .withShutdownTimeout(1.second)
      .withoutBanner
      .bindAny()
      .resource

  test("H2 upgrade from HTTP/1.1 is supported") {
    serverResource.use { server =>
      sendUpgradeRequest(server).map { statusLine =>
        assert(
          statusLine.contains("101"),
          s"Expected 101 Switching Protocols but got: $statusLine"
        )
      }
    }
  }

  private def sendUpgradeRequest(server: Server): IO[String] =
    IO.blocking {
      val addr = server.address
      val socket = new Socket(addr.getHostName, addr.getPort)
      try {
        socket.setSoTimeout(5000)
        val writer = new PrintWriter(socket.getOutputStream, true)
        writer.print(
          s"GET /simple HTTP/1.1\r\n" +
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
