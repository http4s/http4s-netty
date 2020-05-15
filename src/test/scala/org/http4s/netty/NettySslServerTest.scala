package org.http4s.netty

import java.io.ByteArrayInputStream
import java.net.http.{HttpClient, HttpRequest}
import java.net.http.HttpResponse.BodyHandlers
import java.security.KeyStore
import java.security.cert.{CertificateFactory, X509Certificate}

import cats.effect.IO
import fs2.io.tls.TLSParameters
import io.circe.{Decoder, Encoder}
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}
import org.http4s.{EntityEncoder, HttpRoutes}
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.server.{SecureSession, ServerRequestKeys}
import scodec.bits.ByteVector

import scala.util.Try

class NettySslServerTest extends NettySuite {
  lazy val sslContext: SSLContext = {
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

  val server = resourceFixture(
    newServer(sslContext),
    "server"
  )

  val mutualTlsServerRequired = resourceFixture(
    newServer(sslContext, TLSParameters(needClientAuth = true)),
    "mtlsServer"
  )

  private def newServer(ctx: SSLContext, parameters: TLSParameters = TLSParameters.Default) = {
    NettyServerBuilder[IO]
      .withHttpApp(
        HttpRoutes
          .of[IO] {
            case GET -> Root                   => Ok("Hello from TLS")
            case r @ GET -> Root / "cert-info" =>
              r.attributes.lookup(ServerRequestKeys.SecureSession).flatten match {
                case Some(value) => Ok(value)
                case None        => BadRequest()
              }
          }
          .orNotFound
      )
      .withExecutionContext(munitExecutionContext)
      .withoutBanner
      .bindAny()
      .withSslContext(ctx, parameters)
      .resource
  }

  val client = HttpClient.newBuilder().sslContext(sslContext).build()

  test("GET Root over TLS") {
    val s = server()
    client.sendIO(HttpRequest.newBuilder(s.baseUri.toURI).build(), BodyHandlers.ofString()).map { res =>
      assertEquals(res.body(), "Hello from TLS")
    }
  }

  test("GET Cert-Info over TLS") {
    val s   = server()
    val uri = s.baseUri / "cert-info"
    client.sendIO(HttpRequest.newBuilder(uri.toURI).build(), BodyHandlers.ofString()).map { res =>
      val Right(decoded) = io.circe.jawn.decode[SecureSession](res.body())
      assert(decoded.X509Certificate.isEmpty)
    }
  }

  test("mtls GET Root") {
    val s = mutualTlsServerRequired()
    client.sendIO(HttpRequest.newBuilder(s.baseUri.toURI).build(), BodyHandlers.ofString()).map { res =>
      assertEquals(res.body(), "Hello from TLS")
    }
  }

  test("mtls Cert-Info over TLS") {
    val s   = mutualTlsServerRequired()
    val uri = s.baseUri / "cert-info"
    client.sendIO(HttpRequest.newBuilder(uri.toURI).build(), BodyHandlers.ofString()).map { res =>
      val Right(decoded) = io.circe.jawn.decode[SecureSession](res.body())
      assert(decoded.X509Certificate.nonEmpty)
    }
  }
}
