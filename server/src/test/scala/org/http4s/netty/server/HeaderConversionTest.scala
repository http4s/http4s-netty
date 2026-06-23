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
import fs2.Stream
import munit.catseffect.IOFixture
import org.http4s._
import org.http4s.client.Client
import org.http4s.dsl.io._
import org.http4s.headers.`Transfer-Encoding`
import org.http4s.headers.{Connection => ConnHeader}
import org.http4s.implicits._
import org.http4s.netty.client.NettyClientBuilder
import org.http4s.server.Server
import org.typelevel.ci._

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import scala.concurrent.duration._

class HeaderConversionTest extends IOSuite {

  val server: IOFixture[Server] = resourceFixture(
    NettyServerBuilder[IO]
      .withHttpApp(HeaderConversionTest.routes)
      .withNioTransport
      .withIdleTimeout(2.seconds)
      .withShutdownTimeout(1.second)
      .withoutBanner
      .bindAny()
      .resource,
    "server"
  )

  val client: IOFixture[Client[IO]] = resourceFixture(
    NettyClientBuilder[IO]
      .withEventLoopThreads(2)
      .resource,
    "client"
  )

  test("Connection header is properly formatted in response") {
    // This test verifies that Connection header is rendered using the Header type class
    val uri = server().baseUri / "with-connection"
    client().run(Request[IO](uri = uri)).use { res =>
      res.body.compile.drain *> IO {
        val connHeader = res.headers.get[ConnHeader]
        assert(connHeader.isDefined, "Connection header should be present")
        // The raw header value should be "keep-alive", not a toString representation
        val rawValue = res.headers.headers.find(_.name == ci"Connection").map(_.value)
        assertEquals(rawValue, Some("keep-alive"))
        // Also verify no toString garbage
        assert(
          !rawValue.exists(_.contains("Connection(")),
          s"Connection header should not contain toString representation: $rawValue"
        )
      }
    }
  }

  test("Transfer-Encoding with multiple codings is properly formatted") {
    val uri = server().baseUri / "gzip-chunked"
    client().run(Request[IO](uri = uri)).use { res =>
      res.body.compile.drain *> IO {
        // Should have properly formatted transfer-encoding values
        val rawValues = res.headers.headers
          .filter(_.name == ci"Transfer-Encoding")
          .map(_.value)
        // Netty may split or combine these, but they should be valid coding names
        val allValues = rawValues.mkString(", ")
        // Ensure we don't have toString garbage
        assert(
          !allValues.contains("TransferCoding("),
          s"Transfer-Encoding should not contain toString representation: $allValues"
        )
        assert(
          !allValues.contains("NonEmptyList("),
          s"Transfer-Encoding should not contain toString representation: $allValues"
        )
      }
    }
  }

  test("HEAD response omits Transfer-Encoding header") {
    // We intentionally omit Transfer-Encoding on HEAD responses. Adding it caused
    // the standalone HttpResponseEncoder to write illegal chunk framing, and the
    // HttpServerCodec strips it from the wire anyway. Verify it stays absent.
    IO {
      val jdkClient = HttpClient.newHttpClient()
      val request = HttpRequest
        .newBuilder()
        .uri(URI.create(s"${server().baseUri}chunked-head"))
        .method("HEAD", HttpRequest.BodyPublishers.noBody())
        .build()
      val response = jdkClient.send(request, HttpResponse.BodyHandlers.discarding())

      val teHeaders = response.headers().allValues("Transfer-Encoding")
      assert(
        teHeaders.isEmpty,
        s"Transfer-Encoding header should not be present on HEAD response, got: $teHeaders"
      )
    }
  }

  test("HEAD response with chunked TE must not include chunk framing on the wire") {
    // HttpServerCodec (unlike standalone HttpResponseEncoder) tracks the request
    // method, so it suppresses body/chunk framing for HEAD responses. Without this,
    // the encoder writes an illegal "0\r\n\r\n" chunk terminator that violates
    // RFC 9110 Section 9.3.2 (HEAD responses MUST NOT contain a message body).
    IO {
      val address = server().address
      val socket = new java.net.Socket(address.getHostName, address.getPort)
      socket.setSoTimeout(3000)
      try {
        val out = socket.getOutputStream
        val req =
          s"HEAD /chunked-head HTTP/1.1\r\nHost: ${address.getHostName}:${address.getPort}\r\nConnection: close\r\n\r\n"
        out.write(req.getBytes("US-ASCII"))
        out.flush()

        val baos = new java.io.ByteArrayOutputStream()
        val buf = new Array[Byte](4096)
        var n = socket.getInputStream.read(buf)
        while (n >= 0) {
          baos.write(buf, 0, n)
          n = socket.getInputStream.read(buf)
        }
        val raw = baos.toString("US-ASCII")
        val idx = raw.indexOf("\r\n\r\n")
        assert(idx > 0, s"Response must contain header terminator: $raw")
        val afterHeaders = raw.substring(idx + 4)
        assertEquals(
          afterHeaders,
          "",
          s"HEAD response must not contain body/chunk-framing, but got: '$afterHeaders'"
        )
      } finally
        socket.close()
    }
  }
}

object HeaderConversionTest {
  def routes: HttpApp[IO] =
    HttpRoutes
      .of[IO] {
        case HEAD -> Root / "chunked-head" =>
          // For HEAD requests, set the Transfer-Encoding header explicitly
          // This exercises the HEAD-specific code path in toNonWSResponse
          Ok().map(_.withHeaders(`Transfer-Encoding`(TransferCoding.chunked)))

        case GET -> Root / "with-connection" =>
          Ok("hello").map(
            _.withHeaders(ConnHeader(ci"keep-alive"))
          )

        case GET -> Root / "gzip-chunked" =>
          Ok(Stream.emit("hello").covary[IO]).map(
            _.withHeaders(`Transfer-Encoding`(TransferCoding.gzip, TransferCoding.chunked))
          )
      }
      .orNotFound
}
