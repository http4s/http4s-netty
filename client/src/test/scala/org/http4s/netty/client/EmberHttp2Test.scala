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

import cats.data.Kleisli
import cats.effect.IO
import cats.syntax.all._
import com.comcast.ip4s._
import fs2.io.net.tls.TLSContext
import munit.catseffect.IOFixture
import org.http4s._
import org.http4s.client.Client
import org.http4s.dsl.io._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
import org.http4s.server.Server
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import scala.concurrent.duration._

class EmberHttp2Test extends IOSuite {
  val server: IOFixture[Server] = resourceFixture(
    EmberServerBuilder
      .default[IO]
      .withHttpApp(EmberHttp2Test.routes)
      .withPort(port"0")
      .withHttp2
      .withLogger(Slf4jLogger.getLogger)
      .withShutdownTimeout(1.second)
      .withTLS(TLSContext.Builder.forAsync[IO].fromSSLContext(EmberHttp2Test.sslContextForServer))
      .build,
    "server"
  )

  val client: IOFixture[Client[IO]] = resourceFixture(
    NettyClientBuilder[IO].withNioTransport
      .withSSLContext(EmberHttp2Test.sslContextForClient)
      .resource
      .map(client =>
        Client((req: Request[IO]) => client.run(req.withHttpVersion(HttpVersion.`HTTP/2`)))),
    "client"
  )

  test("simple") {
    val uri = server().baseUri / "simple"
    client().expect[String](uri).map(body => assertEquals(body, "simple path"))

  }
  test("repeated simple") {
    val uri = server().baseUri / "simple"
    val op = client().expect[String](uri).map(body => assertEquals(body, "simple path"))
    op.replicateA_(10)
  }

  test("no-content") {
    val uri = server().baseUri / "no-content"
    client().statusFromUri(uri).map { status =>
      assertEquals(status, NoContent)
    }
  }

  test("delayed") {
    val uri = server().baseUri / "delayed"

    client().expect[String](uri).map { body =>
      assertEquals(body, "delayed path")
    }
  }

  test("echo") {
    val uri = server().baseUri / "echo"

    client().expect[String](Request[IO](POST, uri).withEntity("hello")).map { body =>
      assertEquals(body, "hello")
    }
  }
  test("chunked") {
    val uri = server().baseUri / "chunked"

    client().run(Request[IO](POST, uri).withEntity("hello")).use { res =>
      res.as[String].map { body =>
        assert(res.isChunked)
        assertEquals(res.status, Ok)
        assertEquals(body, "hello")
      }
    }
  }

}

object EmberHttp2Test {
  def routes: Kleisli[IO, Request[IO], Response[IO]] =
    HttpRoutes
      .of[IO] {
        case GET -> Root / "simple" => Ok("simple path")
        case req @ POST -> Root / "chunked" =>
          Response[IO](Ok)
            .withEntity(fs2.Stream.eval(req.as[String]))
            .pure[IO]
        case GET -> Root / "timeout" => IO.never
        case GET -> Root / "delayed" =>
          IO.sleep(1.second) *>
            Ok("delayed path")
        case GET -> Root / "no-content" => NoContent()
        case r @ POST -> Root / "echo" =>
          Ok(r.as[String])
        case GET -> Root / "not-found" => NotFound("not found")
      }
      .orNotFound

  def sslContextForServer: SSLContext = {
    val ks = KeyStore.getInstance("PKCS12")
    ks.load(getClass.getResourceAsStream("/teststore.p12"), "password".toCharArray)

    val kmf = KeyManagerFactory.getInstance("SunX509")
    kmf.init(ks, "password".toCharArray)

    val js = KeyStore.getInstance("PKCS12")
    js.load(getClass.getResourceAsStream("/teststore.p12"), "password".toCharArray)

    val tmf = TrustManagerFactory.getInstance("SunX509")
    tmf.init(js)

    val ctx = SSLContext.getInstance("TLS")
    ctx.init(kmf.getKeyManagers, tmf.getTrustManagers, null)
    ctx
  }

  def sslContextForClient: SSLContext = {
    val ks = KeyStore.getInstance("PKCS12")
    ks.load(getClass.getResourceAsStream("/teststore.p12"), "password".toCharArray)

    val kmf = KeyManagerFactory.getInstance("SunX509")
    kmf.init(ks, "password".toCharArray)

    val js = KeyStore.getInstance("PKCS12")
    js.load(getClass.getResourceAsStream("/teststore.p12"), "password".toCharArray)

    val tmf = TrustManagerFactory.getInstance("SunX509")
    tmf.init(js)

    val ctx = SSLContext.getInstance("TLS")
    ctx.init(kmf.getKeyManagers, tmf.getTrustManagers, null)
    ctx
  }
}
