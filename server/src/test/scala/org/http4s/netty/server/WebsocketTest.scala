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

import java.net.http.HttpClient

import cats.effect.IO
import javax.net.ssl.SSLContext
import org.http4s.jdkhttpclient.{JdkWSClient, WSFrame, WSRequest}
import org.http4s.{HttpRoutes, Uri}
import org.http4s.implicits._
import org.http4s.dsl.io._
import org.http4s.server.websocket.WebSocketBuilder
import org.http4s.websocket.WebSocketFrame

class WebsocketTest extends IOSuite {
  val queue = fs2.concurrent.Queue.bounded[IO, WebSocketFrame](1).unsafeRunSync()
  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] { case _ -> Root / "ws" =>
    WebSocketBuilder[IO]
      .build(
        queue.dequeue,
        _.evalMap(ws => queue.enqueue1(ws))
      )
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

  val sslContext: SSLContext = SslServerTest.sslContext
  val tlsServer = resourceFixture(
    SslServerTest.sslServer(routes, sslContext).withWebsockets.resource,
    "tls-server"
  )

  private def runTest(client: HttpClient, wsUrl: Uri, text: WSFrame.Text) =
    JdkWSClient[IO](client).connectHighLevel(WSRequest(wsUrl)).use { highlevel =>
      highlevel.send(text) *> highlevel.sendClose("closing") *>
        highlevel.receiveStream.compile.toList
          .map(list => assertEquals(list, List(text)))
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
