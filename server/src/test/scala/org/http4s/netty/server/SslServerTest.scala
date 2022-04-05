package org.http4s.netty.server

import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.{CertificateFactory, X509Certificate}
import cats.effect.{IO, Async}
import fs2.io.net.tls.TLSParameters
import io.circe.{Decoder, Encoder}

import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}
import org.http4s.client.Client
import org.http4s.{EntityDecoder, EntityEncoder, HttpRoutes}
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.jdkhttpclient.JdkHttpClient
import org.http4s.netty.client.NettyClientBuilder
import org.http4s.server.{SecureSession, Server, ServerRequestKeys}
import scodec.bits.ByteVector

import java.net.http.HttpClient
import scala.util.Try

abstract class SslServerTest(typ: String = "TLS") extends IOSuite {
  lazy val sslContext: SSLContext = SslServerTest.sslContext

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

  implicit val encoderSession: Encoder[SecureSession] =
    Encoder.forProduct4("sessionId", "cipherSuite", "keySize", "client_certificates")(
      SecureSession.unapply(_).get)
  implicit val decoderSession: Decoder[SecureSession] =
    Decoder.forProduct4("sessionId", "cipherSuite", "keySize", "client_certificates")(
      SecureSession.apply(_: String, _: String, _: Int, _: List[X509Certificate])
    )

  implicit val entityEncoder: EntityEncoder[IO, SecureSession] =
    org.http4s.circe.jsonEncoderOf[IO, SecureSession]
  val routes: HttpRoutes[IO] = HttpRoutes
    .of[IO] {
      case GET -> Root => Ok("Hello from TLS")
      case r@GET -> Root / "cert-info" =>
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
  val client = resourceFixture(
    JdkHttpClient[IO](HttpClient.newBuilder().sslContext(sslContext).build()),
    "client")

  val server = resourceFixture(
    SslServerTest.sslServer(routes, sslContext).resource,
    "server"
  )
}

class JDKMTLSServerTest extends SslServerTest("mTLS") {
  val client = resourceFixture(
    JdkHttpClient[IO](HttpClient.newBuilder().sslContext(sslContext).build()),
    "client")

  val server = resourceFixture(
    SslServerTest.sslServer(routes, sslContext, TLSParameters(needClientAuth = true)).resource,
    "mtlsServer"
  )
}

class NettyClientSslServerTest extends SslServerTest() {
  val client = resourceFixture(
    NettyClientBuilder[IO]
      .withSSLContext(sslContext)
      .withEventLoopThreads(2)
      .resource,
    "client"
  )
  val server = resourceFixture(
    SslServerTest.sslServer(routes, sslContext).resource,
    "server"
  )
}

class NettyClientMTLSServerTest extends SslServerTest("mTLS") {
  val client = resourceFixture(
    NettyClientBuilder[IO]
      .withSSLContext(sslContext)
      .resource,
    "client"
  )
  val server = resourceFixture(
    SslServerTest.sslServer(routes, sslContext, TLSParameters(needClientAuth = true)).resource,
    "mtlsServer"
  )
}

object SslServerTest {
  def sslContext: SSLContext = {
    val ks = KeyStore.getInstance("PKCS12")
    ks.load(getClass.getResourceAsStream("/teststore.p12"), "password".toCharArray)

    val kmf = KeyManagerFactory.getInstance("SunX509")
    kmf.init(ks, "password".toCharArray)

    val js = KeyStore.getInstance("PKCS12")
    js.load(getClass.getResourceAsStream("/teststore.p12"), "password".toCharArray)

    val tmf = TrustManagerFactory.getInstance("SunX509")
    tmf.init(js)

    val sc = SSLContext.getInstance("TLSv1.2")
    sc.init(kmf.getKeyManagers, tmf.getTrustManagers, null)

    sc
  }

  def sslServer(
                 routes: HttpRoutes[IO],
                 ctx: SSLContext,
                 parameters: TLSParameters = TLSParameters.Default)(implicit
                                                                    eff: Async[IO]
               ): NettyServerBuilder[IO] =
    NettyServerBuilder[IO]
      .withHttpApp(routes.orNotFound)
      .withEventLoopThreads(10)
      .withoutBanner
      .bindAny()
      .withSslContext(ctx, parameters)
}
