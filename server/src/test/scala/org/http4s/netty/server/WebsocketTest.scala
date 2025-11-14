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
import cats.syntax.all._
import munit.catseffect.IOFixture
import org.http4s.HttpRoutes
import org.http4s.Uri
import org.http4s.client.websocket.WSClient
import org.http4s.client.websocket.WSFrame
import org.http4s.client.websocket.WSRequest
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.jdkhttpclient.JdkWSClient
import org.http4s.netty.client.NettyWSClientBuilder
import org.http4s.netty.server.websocket.ZeroCopyBinaryText
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import scodec.bits.ByteVector

import java.net.http.HttpClient
import scala.concurrent.duration.DurationInt

abstract class WebsocketTest(_client: Resource[IO, WSClient[IO]]) extends IOSuite {

  import WebsocketTest._

  val server: IOFixture[Uri] = resourceFixture(
    for {
      netty <- NettyServerBuilder[IO]
        .withHttpWebSocketApp(echoRoutes(_).orNotFound)
        .withNioTransport
        .withoutBanner
        .bindAny()
        .resource
        .map(s => httpToWsUri(s.baseUri))
    } yield netty,
    "server"
  )

  val client: IOFixture[WSClient[IO]] = resourceFixture(_client, "client")

  def httpToWsUri(uri: Uri): Uri =
    uri.copy(scheme = Uri.Scheme.unsafeFromString("ws").some) / "echo"

  protected def testLowLevel: IO[Unit] =
    client()
      .connect(WSRequest(server()))
      .use { conn =>
        for {
          _ <- conn.send(WSFrame.Text("bar"))
          _ <- conn.send(WSFrame.Text("zero-copy"))
          _ <- conn.sendMany(List(WSFrame.Binary(ByteVector(3, 99, 12)), WSFrame.Text("foo")))
          _ <- conn.send(WSFrame.Close(1000, "goodbye"))
          recv <- conn.receiveStream.compile.toList
        } yield recv
      }
      .assertEquals(
        List(
          WSFrame.Text("bar"),
          WSFrame.Text("zero-copy"),
          WSFrame.Binary(ByteVector(3, 99, 12)),
          WSFrame.Text("foo"),
          WSFrame.Close(1000, "goodbye")
        )
      )

  test("send and receive frames in high-level mode") {
    client()
      .connectHighLevel(WSRequest(server()))
      .use { conn =>
        for {
          _ <- conn.send(WSFrame.Binary(ByteVector(15, 2, 3)))
          _ <- conn.sendMany(
            List(
              WSFrame.Text("foo"),
              WSFrame.Text("bar"),
              WSFrame.Text("zero-copy"),
              WSFrame.Text("zero-copy")))
          recv <- conn.receiveStream.take(4).compile.toList
        } yield recv
      }
      .assertEquals(
        List(
          WSFrame.Binary(ByteVector(15, 2, 3)),
          WSFrame.Text("foo"),
          WSFrame.Text("bar"),
          WSFrame.Text("zero-copy"),
          WSFrame.Text("zero-copy-unsafe")
        )
      )
  }
}

object WebsocketTest {
  def echoRoutes(ws: WebSocketBuilder2[IO]): HttpRoutes[IO] =
    HttpRoutes.of[IO] { case _ -> Root / "echo" =>
      ws.build {
        _.map {
          case t: WebSocketFrame.Text if t.str == "zero-copy-unsafe" =>
            ZeroCopyBinaryText.unsafe("zero-copy-unsafe".getBytes, last = true)
          case t: WebSocketFrame.Text if t.str == "zero-copy" =>
            ZeroCopyBinaryText(ByteVector("zero-copy".getBytes), last = true)
          case frame => frame
        }
      }
    }
}

class NettyWebsocketTest
    extends WebsocketTest(
      NettyWSClientBuilder[IO].withIdleTimeout(5.seconds).withNioTransport.resource) {
  test("send and receive frames in low-level mode") {
    testLowLevel
  }
}

class JDKClientWebsocketTest
    extends WebsocketTest(Resource.pure(JdkWSClient[IO](HttpClient.newHttpClient()))) {
  test("send and receive frames in low-level mode") {
    testLowLevel
  }

  test("group frames by their `last` attribute in high-level mode".flaky) {
    val uri = server()
    client()
      .connectHighLevel(WSRequest(uri))
      .use { conn =>
        for {
          _ <- conn.sendMany(
            List(
              WSFrame.Text("1", last = false),
              WSFrame.Text("2", last = false),
              WSFrame.Text("3"),
              WSFrame.Binary(ByteVector(1)),
              WSFrame.Binary(ByteVector(2), last = false),
              WSFrame.Binary(ByteVector(3), last = false),
              WSFrame.Binary(ByteVector(4)),
              WSFrame.Text("4", last = false),
              WSFrame.Text("5"),
              WSFrame.Binary(ByteVector(5), last = false),
              WSFrame.Binary(ByteVector(6)),
              WSFrame.Text("6"),
              WSFrame.Binary(ByteVector(7), last = false)
            )
          )
          recv <- conn.receiveStream.take(6).compile.toList
        } yield recv
      }
      .assertEquals(
        List(
          WSFrame.Text("123"),
          WSFrame.Binary(ByteVector(1)),
          WSFrame.Binary(ByteVector(2, 3, 4)),
          WSFrame.Text("45"),
          WSFrame.Binary(ByteVector(5, 6)),
          WSFrame.Text("6")
        )
      )
  }

}
