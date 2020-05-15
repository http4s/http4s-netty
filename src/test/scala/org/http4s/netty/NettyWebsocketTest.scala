package org.http4s.netty

import java.net.http.HttpClient

import cats.effect.IO
import javax.net.ssl.SSLContext
import org.http4s.client.jdkhttpclient.{JdkWSClient, WSFrame, WSRequest}
import org.http4s.{HttpRoutes, Uri}
import org.http4s.implicits._
import org.http4s.dsl.io._
import org.http4s.server.websocket.WebSocketBuilder
import org.http4s.websocket.WebSocketFrame

class NettyWebsocketTest extends NettySuite {
  val queue                  = fs2.concurrent.Queue.bounded[IO, WebSocketFrame](1).unsafeRunSync()
  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case _ -> Root / "ws" => {
      WebSocketBuilder[IO]
        .build(
          queue.dequeue,
          _.evalMap(ws => queue.enqueue1(ws))
        )
    }
  }

  val server = resourceFixture(
    NettyServerBuilder[IO]
      .withHttpApp(routes.orNotFound)
      .withExecutionContext(munitExecutionContext)
      .withWebsockets
      .withoutBanner
      .bindAny()
      .resource,
    "server"
  )

  val sslContext: SSLContext = NettySslServerTest.sslContext
  val tlsServer              = resourceFixture(
    NettySslServerTest.sslServer(routes, sslContext).withWebsockets.resource,
    "tls-server"
  )

  private def runTest(client: HttpClient, wsUrl: Uri, text: WSFrame.Text) = {
    JdkWSClient[IO](client).connectHighLevel(WSRequest(wsUrl)).use { highlevel =>
      highlevel.send(text) *> highlevel.sendClose("closing") *>
        highlevel.receiveStream.compile.toList
          .map(list => assertEquals(list, List(text)))
    }
  }

  test("Websocket tests") {
    val s     = server()
    val wsUrl = s.baseUri.copy(Some(Uri.Scheme.unsafeFromString("ws"))) / "ws"

    runTest(HttpClient.newHttpClient(), wsUrl, WSFrame.Text("Hello"))
  }

  test("Websocket TLS tests") {
    val s     = tlsServer()
    val wsUrl = s.baseUri.copy(Some(Uri.Scheme.unsafeFromString("wss"))) / "ws"
    runTest(HttpClient.newBuilder().sslContext(sslContext).build(), wsUrl, WSFrame.Text("Hello"))
  }
}
