package org.http4s.netty

import java.io.ByteArrayInputStream
import java.net.http.HttpClient
import java.security.KeyStore
import java.security.cert.{CertificateFactory, X509Certificate}

import cats.effect.{ConcurrentEffect, IO}
import fs2.io.tls.TLSParameters
import io.circe.{Decoder, Encoder}
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}
import org.http4s.client.jdkhttpclient.JdkHttpClient
import org.http4s.{EntityDecoder, EntityEncoder, HttpRoutes}
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.server.{SecureSession, ServerRequestKeys}
import scodec.bits.ByteVector

import scala.util.Try

class SslServerTest extends IOSuite {
  lazy val sslContext: SSLContext = SslServerTest.sslContext

  implicit val x509Encoder: Encoder[X509Certificate] = Encoder.encodeString.contramap { x =>
    ByteVector(x.getEncoded).toBase64
  }
  implicit val x509Decoder: Decoder[X509Certificate] = Decoder.decodeString
    .emap(ByteVector.fromBase64Descriptive(_))
    .emapTry(base64 =>
      Try {
        val cf = CertificateFactory.getInstance("X509")
        cf.generateCertificate(new ByteArrayInputStream(base64.toArray)).asInstanceOf[X509Certificate]
      }
    )

  implicit val encoderSession: Encoder[SecureSession] =
    Encoder.forProduct4("sessionId", "cipherSuite", "keySize", "client_certificates")(SecureSession.unapply(_).get)
  implicit val decoderSession: Decoder[SecureSession] =
    Decoder.forProduct4("sessionId", "cipherSuite", "keySize", "client_certificates")(
      SecureSession.apply(_: String, _: String, _: Int, _: List[X509Certificate])
    )

  implicit val entityEncoder: EntityEncoder[IO, SecureSession] = org.http4s.circe.jsonEncoderOf[IO, SecureSession]
  val routes: HttpRoutes[IO]                                   = HttpRoutes
    .of[IO] {
      case GET -> Root                   => Ok("Hello from TLS")
      case r @ GET -> Root / "cert-info" =>
        r.attributes.lookup(ServerRequestKeys.SecureSession).flatten match {
          case Some(value) => Ok(value)
          case None        => BadRequest()
        }
    }

  val server = resourceFixture(
    SslServerTest.sslServer(routes, sslContext).resource,
    "server"
  )

  val mutualTlsServerRequired = resourceFixture(
    SslServerTest.sslServer(routes, sslContext, TLSParameters(needClientAuth = true)).resource,
    "mtlsServer"
  )

  val client = JdkHttpClient[IO](HttpClient.newBuilder().sslContext(sslContext).build())

  test("GET Root over TLS") {
    val s = server()
    client.expect[String](s.baseUri).map { res =>
      assertEquals(res, "Hello from TLS")
    }
  }

  test("GET Cert-Info over TLS") {
    implicit val entityDecoder: EntityDecoder[IO, SecureSession] = org.http4s.circe.jsonOf[IO, SecureSession]

    val s   = server()
    val uri = s.baseUri / "cert-info"
    client.expect[SecureSession](uri).map { res =>
      assert(res.X509Certificate.isEmpty)
    }
  }

  test("mtls GET Root") {
    val s = mutualTlsServerRequired()
    client.expect[String](s.baseUri).map { res =>
      assertEquals(res, "Hello from TLS")
    }
  }

  test("mtls Cert-Info over TLS") {
    implicit val entityDecoder: EntityDecoder[IO, SecureSession] = org.http4s.circe.jsonOf[IO, SecureSession]

    val s   = mutualTlsServerRequired()
    val uri = s.baseUri / "cert-info"
    client.expect[SecureSession](uri).map { res =>
      assert(res.X509Certificate.nonEmpty)
    }
  }
}

object SslServerTest {
  def sslContext: SSLContext = {
    val ks = KeyStore.getInstance("JKS")
    ks.load(getClass.getResourceAsStream("/teststore.jks"), "password".toCharArray)

    val kmf = KeyManagerFactory.getInstance("SunX509")
    kmf.init(ks, "password".toCharArray)

    val js = KeyStore.getInstance("JKS")
    js.load(getClass.getResourceAsStream("/teststore.jks"), "password".toCharArray)

    val tmf = TrustManagerFactory.getInstance("SunX509")
    tmf.init(js)

    val sc = SSLContext.getInstance("TLSv1.2")
    sc.init(kmf.getKeyManagers, tmf.getTrustManagers, null)

    sc
  }

  def sslServer(routes: HttpRoutes[IO], ctx: SSLContext, parameters: TLSParameters = TLSParameters.Default)(implicit
      eff: ConcurrentEffect[IO]
  ): NettyServerBuilder[IO] =
    NettyServerBuilder[IO]
      .withHttpApp(routes.orNotFound)
      .withoutBanner
      .bindAny()
      .withSslContext(ctx, parameters)
}
