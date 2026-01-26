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
import scala.jdk.CollectionConverters._

class HeaderConversionTest extends IOSuite {

  val server: IOFixture[Server] = resourceFixture(
    NettyServerBuilder[IO]
      .withHttpApp(HeaderConversionTest.routes)
      .withNioTransport
      .withIdleTimeout(2.seconds)
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

  test("HEAD request returns properly formatted Transfer-Encoding header") {
    // This test specifically exercises the HEAD request code path in toNonWSResponse
    // which has separate logic for adding Transfer-Encoding headers.
    // The bug was using enc.toString instead of enc.value, which would produce
    // "Transfer-Encoding(NonEmptyList(TransferCoding(chunked)))" instead of "chunked"
    //
    // Note: We use JDK HttpClient here because the http4s NettyClient strips the
    // Transfer-Encoding header from HEAD responses (since there's no body).
    // We need to see the raw wire-level headers to verify server behavior.
    IO {
      val jdkClient = HttpClient.newHttpClient()
      val request = HttpRequest
        .newBuilder()
        .uri(URI.create(s"${server().baseUri}chunked-head"))
        .method("HEAD", HttpRequest.BodyPublishers.noBody())
        .build()
      val response = jdkClient.send(request, HttpResponse.BodyHandlers.discarding())

      val teHeaders = response.headers().allValues("Transfer-Encoding").asScala.toList
      val allValues = teHeaders.mkString(", ")

      // The header should be present and properly formatted
      assert(teHeaders.nonEmpty, s"Transfer-Encoding header should be present for HEAD request")
      assert(
        allValues.contains("chunked"),
        s"Transfer-Encoding should contain 'chunked', got: $allValues"
      )
      // Critical: verify no toString garbage
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
