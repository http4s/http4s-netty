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

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import scala.concurrent.duration._

/** Verify that HTTP/1.1 pipelined responses are sent in request order (RFC 9112 §9.3.2).
  *
  * Sends a slow request followed by a fast request on the same connection in one write. The server
  * must respond with the slow response first, even though the fast one completes sooner.
  *
  * @see
  *   https://github.com/http4s/http4s-netty/issues/1019
  */
class PipeliningOrderTest extends CatsEffectSuite {

  private def serverResource: Resource[IO, Server] = {
    val routes = HttpRoutes
      .of[IO] {
        case GET -> Root / "slow" =>
          IO.sleep(500.millis) *> Ok("slow-body")
        case GET -> Root / "fast" =>
          Ok("fast-body")
      }
      .orNotFound

    NettyServerBuilder[IO]
      .withHttpApp(routes)
      .withEventLoopThreads(2)
      .withShutdownTimeout(1.second)
      .withoutBanner
      .bindAny()
      .resource
  }

  test("pipelined responses arrive in request order") {
    serverResource.use { server =>
      val addr = server.address
      Resource
        .fromAutoCloseable(IO.blocking(new Socket(addr.getHostName, addr.getPort)))
        .use { socket =>
          IO.blocking {
            socket.setSoTimeout(10000)
            val writer = new PrintWriter(socket.getOutputStream, true)

            // Pipeline two requests in one write: slow first, fast second
            writer.print(
              s"GET /slow HTTP/1.1\r\n" +
                s"Host: ${addr.getHostName}:${addr.getPort}\r\n" +
                "\r\n" +
                s"GET /fast HTTP/1.1\r\n" +
                s"Host: ${addr.getHostName}:${addr.getPort}\r\n" +
                "Connection: close\r\n" +
                "\r\n"
            )
            writer.flush()

            val reader = new BufferedReader(new InputStreamReader(socket.getInputStream))

            val first = readHttpResponseBody(reader)
            val second = readHttpResponseBody(reader)

            assertEquals(first, "slow-body", "first response must be for the first (slow) request")
            assertEquals(
              second,
              "fast-body",
              "second response must be for the second (fast) request")
          }
        }
    }
  }

  /** Read one HTTP/1.1 response and return its body. Handles both Content-Length and chunked
    * transfer encoding.
    */
  private def readHttpResponseBody(reader: BufferedReader): String = {
    // Read status line
    val statusLine = reader.readLine()
    assert(statusLine != null && statusLine.startsWith("HTTP/"), s"Expected HTTP status line")

    // Read headers
    var contentLength = -1
    var chunked = false
    var line = reader.readLine()
    while (line != null && line.nonEmpty) {
      val lower = line.toLowerCase
      if (lower.startsWith("content-length:"))
        contentLength = line.substring("content-length:".length).trim.toInt
      if (lower.startsWith("transfer-encoding:") && lower.contains("chunked"))
        chunked = true
      line = reader.readLine()
    }

    if (chunked) readChunkedBody(reader)
    else if (contentLength >= 0) {
      val buf = new Array[Char](contentLength)
      var read = 0
      while (read < contentLength) {
        val n = reader.read(buf, read, contentLength - read)
        assert(n > 0, "Unexpected end of stream")
        read += n
      }
      new String(buf)
    } else
      fail(s"Response has neither Content-Length nor chunked Transfer-Encoding: $statusLine")
  }

  private def readChunkedBody(reader: BufferedReader): String = {
    val sb = new StringBuilder
    var chunkSize = Integer.parseInt(reader.readLine().trim, 16)
    while (chunkSize > 0) {
      val buf = new Array[Char](chunkSize)
      var read = 0
      while (read < chunkSize) {
        val n = reader.read(buf, read, chunkSize - read)
        assert(n > 0, "Unexpected end of stream in chunk")
        read += n
      }
      sb.appendAll(buf)
      reader.readLine() // consume trailing CRLF
      chunkSize = Integer.parseInt(reader.readLine().trim, 16)
    }
    reader.readLine() // consume final CRLF after 0-chunk
    sb.toString
  }
}
