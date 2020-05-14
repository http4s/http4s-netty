package org.http4s.netty

import java.net.URI
import java.net.http.{HttpClient, HttpRequest}
import java.net.http.HttpResponse.BodyHandlers
import java.security.KeyStore

import cats.effect.IO
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}
import org.http4s.HttpRoutes
import org.http4s.dsl.io._
import org.http4s.implicits._

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

  val server = resourceFixture(
    NettyServerBuilder[IO]
      .withHttpApp(
        HttpRoutes
          .of[IO] {
            case GET -> Root => Ok("Hello from TLS")
          }
          .orNotFound
      )
      .withExecutionContext(munitExecutionContext)
      .withoutBanner
      .bindAny()
      .withSslContext(sslContext)
      .resource,
    "server"
  )

  override def munitFixtures: Seq[Fixture[_]] = List(server)

  val client = HttpClient.newBuilder().sslContext(sslContext).build()

  test("GET Root over TLS") {
    val s   = server()
    val uri = URI.create(s.baseUri.renderString)
    client.sendIO(HttpRequest.newBuilder(uri).build(), BodyHandlers.ofString()).map { res =>
      assertEquals(res.body(), "Hello from TLS")
    }
  }
}
