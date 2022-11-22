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

import cats.effect.Async
import cats.effect.IO
import cats.effect.Resource
import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import io.circe.syntax.KeyOps
import io.netty.handler.ssl.ClientAuth
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import org.http4s.EntityDecoder
import org.http4s.EntityEncoder
import org.http4s.HttpRoutes
import org.http4s.client.Client
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.jdkhttpclient.JdkHttpClient
import org.http4s.netty.client.NettyClientBuilder
import org.http4s.server.SecureSession
import org.http4s.server.Server
import org.http4s.server.ServerRequestKeys
import org.http4s.server.websocket.WebSocketBuilder2
import scodec.bits.ByteVector

import java.io.ByteArrayInputStream
import java.net.http.HttpClient
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import scala.util.Try

abstract class SslServerTest(typ: String = "TLS") extends IOSuite {
  // lazy val sslContext: SslContext = SslServerTest.sslContextForServer.build()

  implicit val x509Encoder: Encoder[X509Certificate] = Encoder.encodeString.contramap { x =>
    ByteVector(x.getEncoded).toBase64
  }
  implicit val x509Decoder: Decoder[X509Certificate] = Decoder.decodeString
    .emap(ByteVector.fromBase64Descriptive(_))
    .emapTry(base64 =>
      Try {
        val cf = CertificateFactory.getInstance("X509")
        cf.generateCertificate(new ByteArrayInputStream(base64.toArray))
          .asInstanceOf[X509Certificate]
      })

  implicit val encoderSession: Encoder[SecureSession] = Encoder.instance { session =>
    Json.obj(
      "sessionId" := session.sslSessionId,
      "cipherSuite" := session.cipherSuite,
      "keySize" := session.keySize,
      "client_certificates" := session.X509Certificate
    )
  }
  implicit val decoderSession: Decoder[SecureSession] =
    Decoder.forProduct4("sessionId", "cipherSuite", "keySize", "client_certificates")(
      SecureSession.apply(_: String, _: String, _: Int, _: List[X509Certificate])
    )

  implicit val entityEncoder: EntityEncoder[IO, SecureSession] =
    org.http4s.circe.jsonEncoderOf[IO, SecureSession]
  val routes: HttpRoutes[IO] = HttpRoutes
    .of[IO] {
      case GET -> Root => Ok("Hello from TLS")
      case r @ GET -> Root / "cert-info" =>
        r.attributes.lookup(ServerRequestKeys.SecureSession).flatten match {
          case Some(value) => Ok(value)
          case None => BadRequest()
        }
    }

  def server: Fixture[Server]

  def client: Fixture[Client[IO]]

  test(s"GET Root over $typ") {
    /*(server: Server[IO], client: Client[IO]) =>*/
    val s = server()
    client().expect[String](s.baseUri).map { res =>
      assertEquals(res, "Hello from TLS")
    }
  }

  test(s"GET Cert-Info over $typ") {
    /*(server: Server[IO], client: Client[IO]) =>*/
    implicit val entityDecoder: EntityDecoder[IO, SecureSession] =
      org.http4s.circe.jsonOf[IO, SecureSession]

    val s = server()
    val uri = s.baseUri / "cert-info"
    client().expect[SecureSession](uri).map { res =>
      typ match {
        case "mTLS" => assert(res.X509Certificate.nonEmpty)
        case _ => assert(res.X509Certificate.isEmpty)
      }

    }
  }
}

class JDKSslServerTest extends SslServerTest() {
  val client: Fixture[Client[IO]] = resourceFixture(
    Resource.pure(
      JdkHttpClient[IO](
        HttpClient.newBuilder().sslContext(SslServerTest.sslContextForClient).build())),
    "client")

  val server: Fixture[Server] = resourceFixture(
    SslServerTest.sslServer(_ => routes, SslServerTest.sslContextForServer.build()).resource,
    "server"
  )
}

class JDKMTLSServerTest extends SslServerTest("mTLS") {
  val client: Fixture[Client[IO]] = resourceFixture(
    Resource.pure(
      JdkHttpClient[IO](
        HttpClient.newBuilder().sslContext(SslServerTest.sslContextForClient).build())),
    "client")

  val server: Fixture[Server] = resourceFixture(
    SslServerTest
      .sslServer(
        _ => routes,
        SslServerTest.sslContextForServer.clientAuth(ClientAuth.REQUIRE).build())
      .resource,
    "mtlsServer"
  )
}

class NettyClientSslServerTest extends SslServerTest() {
  val client: Fixture[Client[IO]] = resourceFixture(
    NettyClientBuilder[IO]
      .withSSLContext(SslServerTest.sslContextForClient)
      .withEventLoopThreads(2)
      .resource,
    "client"
  )
  val server: Fixture[Server] = resourceFixture(
    SslServerTest.sslServer(_ => routes, SslServerTest.sslContextForServer.build()).resource,
    "server"
  )
}

class NettyClientMTLSServerTest extends SslServerTest("mTLS") {
  val client: Fixture[Client[IO]] = resourceFixture(
    NettyClientBuilder[IO]
      .withSSLContext(SslServerTest.sslContextForClient)
      .resource,
    "client"
  )
  val server: Fixture[Server] = resourceFixture(
    SslServerTest
      .sslServer(
        _ => routes,
        SslServerTest.sslContextForServer.clientAuth(ClientAuth.REQUIRE).build())
      .resource,
    "mtlsServer"
  )
}

object SslServerTest {
  def sslContextForServer: SslContextBuilder = {
    val ks = KeyStore.getInstance("PKCS12")
    ks.load(getClass.getResourceAsStream("/teststore.p12"), "password".toCharArray)

    val kmf = KeyManagerFactory.getInstance("SunX509")
    kmf.init(ks, "password".toCharArray)

    val js = KeyStore.getInstance("PKCS12")
    js.load(getClass.getResourceAsStream("/teststore.p12"), "password".toCharArray)

    val tmf = TrustManagerFactory.getInstance("SunX509")
    tmf.init(js)

    SslContextBuilder.forServer(kmf).trustManager(tmf)
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

  def sslServer(routes: WebSocketBuilder2[IO] => HttpRoutes[IO], ctx: SslContext)(implicit
      eff: Async[IO]
  ): NettyServerBuilder[IO] =
    NettyServerBuilder[IO]
      .withHttpWebSocketApp(routes(_).orNotFound)
      .withNioTransport
      .withoutBanner
      .bindAny()
      .withSslContext(ctx)
}
