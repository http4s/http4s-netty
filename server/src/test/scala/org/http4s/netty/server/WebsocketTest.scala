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
import cats.effect.{IO, Resource}
import cats.effect.std.Queue

import javax.net.ssl.SSLContext
import org.http4s.jdkhttpclient.{JdkWSClient, WSFrame, WSRequest}
import org.http4s.{HttpRoutes, Uri}
import org.http4s.implicits._
import org.http4s.dsl.io._
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame

class WebsocketTest extends IOSuite {
  def echoRoutes(ws: WebSocketBuilder2[IO], queue: Queue[IO, WebSocketFrame]): HttpRoutes[IO] =
    HttpRoutes.of[IO] { case _ -> Root / "ws" =>
      ws.build(
        fs2.Stream.fromQueueUnterminated(queue, 2),
        _.evalMap(frame =>
          queue.offer(frame).flatMap(_ => queue.offer(WebSocketFrame.Close(1000).toOption.get))))
    }

  val server = resourceFixture(
    for {
      queue <- Resource.eval(Queue.bounded[IO, WebSocketFrame](2))
      netty <- NettyServerBuilder[IO]
        .withHttpWebSocketApp(echoRoutes(_, queue).orNotFound)
        .withoutBanner
        .bindAny()
        .resource
    } yield netty,
    "server"
  )

  val sslContext: SSLContext = SslServerTest.sslContext
  val tlsServer = resourceFixture(
    for {
      queue <- Resource.eval(Queue.bounded[IO, WebSocketFrame](2))
      netty <- SslServerTest.sslServer(echoRoutes(_, queue), sslContext).resource
    } yield netty,
    "tls-server"
  )

  private def runTest(client: HttpClient, wsUrl: Uri, text: WSFrame.Text) =
    JdkWSClient[IO](client).use {
      _.connectHighLevel(WSRequest(wsUrl)).use { highlevel =>
        highlevel.send(text) *>
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
