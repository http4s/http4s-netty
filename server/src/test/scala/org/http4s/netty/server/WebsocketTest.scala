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

import cats.effect.IO
import cats.effect.Resource
import cats.effect.std.Queue
import io.netty.handler.ssl.SslContext
import org.http4s.HttpRoutes
import org.http4s.Uri
import org.http4s.client.websocket.WSClient
import org.http4s.client.websocket.WSFrame
import org.http4s.client.websocket.WSRequest
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.jdkhttpclient.JdkWSClient
import org.http4s.netty.client.NettyWSClientBuilder
import org.http4s.server.Server
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame

import java.net.http.HttpClient
import javax.net.ssl.SSLContext

abstract class WebsocketTest(client: (Option[SSLContext]) => Resource[IO, WSClient[IO]])
    extends IOSuite {
  def echoRoutes(ws: WebSocketBuilder2[IO], queue: Queue[IO, WebSocketFrame]): HttpRoutes[IO] =
    HttpRoutes.of[IO] { case _ -> Root / "websocket" =>
      ws.build(
        fs2.Stream.fromQueueUnterminated(queue, 2),
        _.evalMap(frame =>
          queue.offer(frame).flatMap(_ => queue.offer(WebSocketFrame.Close(1000).toOption.get))))
    }

  val server: Fixture[Server] = resourceFixture(
    for {
      queue <- Resource.eval(Queue.bounded[IO, WebSocketFrame](2))
      netty <- NettyServerBuilder[IO]
        .withHttpWebSocketApp(echoRoutes(_, queue).orNotFound)
        .withNioTransport
        .withoutBanner
        .bindAny()
        .resource
    } yield netty,
    "server"
  )

  val sslContext: SslContext = SslServerTest.sslContextForServer.build()
  val tlsServer: Fixture[Server] = resourceFixture(
    for {
      queue <- Resource.eval(Queue.bounded[IO, WebSocketFrame](2))
      netty <- SslServerTest.sslServer(echoRoutes(_, queue), sslContext).resource
    } yield netty,
    "tls-server"
  )

  private def runTest(wsUrl: Uri, text: WSFrame.Text) = {
    val maybeSSL =
      wsUrl.scheme.flatMap(s =>
        if (s.value == "wss") Some(SslServerTest.sslContextForClient) else None)

    client(maybeSSL).use {
      _.connectHighLevel(WSRequest(wsUrl)).use { highlevel =>
        highlevel.send(text) *>
          highlevel.receiveStream.compile.toList
            .map(list => assertEquals(list, List(text)))
      }
    }
  }

  test("Websocket tests") {
    val s = server()
    val wsUrl = s.baseUri.copy(Some(Uri.Scheme.unsafeFromString("ws"))) / "websocket"

    runTest(wsUrl, WSFrame.Text("Hello"))
  }

  test("Websocket TLS tests") {
    val s = tlsServer()
    val wsUrl = s.baseUri.copy(Some(Uri.Scheme.unsafeFromString("wss"))) / "websocket"
    runTest(wsUrl, WSFrame.Text("Hello"))
  }
}

class NettyWebsocketTest
    extends WebsocketTest({
      case Some(ctx) => NettyWSClientBuilder[IO].withSSLContext(ctx).withNioTransport.resource
      case None => NettyWSClientBuilder[IO].withNioTransport.resource
    })

class JDKClientWebsocketTest
    extends WebsocketTest({
      case Some(ctx) => JdkWSClient[IO](HttpClient.newBuilder().sslContext(ctx).build())
      case None => JdkWSClient[IO](HttpClient.newHttpClient())
    })
