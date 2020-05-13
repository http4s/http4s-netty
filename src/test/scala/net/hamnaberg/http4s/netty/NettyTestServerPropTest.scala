package net.hamnaberg.http4s.netty

import java.net.URI
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest}

import cats.effect.IO

import scala.concurrent.duration._
import org.scalacheck.Prop._

class NettyTestServerPropTest extends IOSuite with munit.ScalaCheckSuite {

  val server = resourceFixture(
    NettyServerBuilder[IO]
      .withHttpApp(NettyServerTest.routes)
      .withIdleTimeout(2.seconds)
      .withExcutionContext(munitExecutionContext)
      .bindAny()
      .resource,
    "server"
  )
  val client = HttpClient.newHttpClient()

  override def munitFixtures: Seq[Fixture[_]] = List(server)

  property("POST chunked body") {
    forAll { (body: String) =>
      val s   = server()
      val url = s.baseUri / "chunked"
      assertEquals(
        client
          .send(
            HttpRequest.newBuilder(URI.create(url.renderString)).POST(BodyPublishers.ofString(body)).build(),
            BodyHandlers.ofString()
          )
          .body(),
        body
      )
    }
  }

  property("POST normal body") {
    forAll { (body: String) =>
      val s   = server()
      val url = s.baseUri / "echo"
      assertEquals(
        client
          .send(
            HttpRequest.newBuilder(URI.create(url.renderString)).POST(BodyPublishers.ofString(body)).build(),
            BodyHandlers.ofString()
          )
          .body(),
        body
      )
    }
  }
}
