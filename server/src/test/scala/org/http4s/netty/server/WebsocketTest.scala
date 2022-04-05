package org.http4s.netty.server

import java.net.http.HttpClient
import cats.effect.IO

import javax.net.ssl.SSLContext
import org.http4s.jdkhttpclient.{JdkWSClient, WSFrame, WSRequest}
import org.http4s.{HttpRoutes, Uri}
import org.http4s.implicits._
import org.http4s.dsl.io._
import org.http4s.server.websocket.WebSocketBuilder2

class WebsocketTest extends IOSuite {
  def echoRoutes(ws: WebSocketBuilder2[IO]): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case _ -> Root / "ws" =>
      ws.build(identity)
  }

  val server = resourceFixture(
    NettyServerBuilder[IO]
      .withHttpWebSocketApp(echoRoutes(_).orNotFound)
      .withoutBanner
      .bindAny()
      .resource,
    "server"
  )

  val sslContext: SSLContext = SslServerTest.sslContext
  val tlsServer = resourceFixture(
    SslServerTest.sslServer(echoRoutes, sslContext).resource,
    "tls-server"
  )

  private def runTest(client: HttpClient, wsUrl: Uri, text: WSFrame.Text) =
    JdkWSClient[IO](client).use {
      _.connectHighLevel(WSRequest(wsUrl)).use { highlevel =>
        highlevel.send(text) *> highlevel.sendClose("closing") *>
          highlevel.receiveStream.compile.toList
            .map(list => assertEquals(list, List(text)))
      }
    }

  test("Websocket tests") {
    val s = server()
    val wsUrl = s.baseUri.copy(Some(Uri.Scheme.unsafeFromString("ws"))) / "ws"

    runTest(HttpClient.newHttpClient(), wsUrl, WSFrame.Text("Hello"))
  }

  test("Websocket TLS tests") {
    val s = tlsServer()
    val wsUrl = s.baseUri.copy(Some(Uri.Scheme.unsafeFromString("wss"))) / "ws"
    runTest(HttpClient.newBuilder().sslContext(sslContext).build(), wsUrl, WSFrame.Text("Hello"))
  }
}
