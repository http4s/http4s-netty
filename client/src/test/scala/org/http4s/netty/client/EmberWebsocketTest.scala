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

package org.http4s.netty.client

import cats.effect.IO
import cats.syntax.all._
import com.comcast.ip4s._
import munit.catseffect.IOFixture
import org.http4s._
import org.http4s.client.websocket._
import org.http4s.dsl.io._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.websocket.WebSocketBuilder2
import scodec.bits.ByteVector

import scala.concurrent.duration._

class EmberWebsocketTest extends IOSuite {
  val server: IOFixture[Uri] = resourceFixture(
    for {
      netty <- EmberServerBuilder
        .default[IO]
        .withHttpWebSocketApp(echoRoutes(_).orNotFound)
        .withPort(port"19999")
        .withShutdownTimeout(100.milli)
        .build
        .map(s => httpToWsUri(s.baseUri))
    } yield netty,
    "server"
  )

  val client: IOFixture[WSClient[IO]] =
    resourceFixture(
      NettyWSClientBuilder[IO].withIdleTimeout(5.seconds).withNioTransport.resource,
      "client")

  def httpToWsUri(uri: Uri): Uri =
    uri.copy(scheme = Uri.Scheme.unsafeFromString("ws").some) / "echo"

  test("send and receive frames in low-level mode".flaky) {
    client()
      .connect(WSRequest(server()))
      .use { conn =>
        for {
          _ <- conn.send(WSFrame.Text("bar"))
          _ <- conn.sendMany(List(WSFrame.Binary(ByteVector(3, 99, 12)), WSFrame.Text("foo")))
          _ <- conn.send(WSFrame.Close(1000, "goodbye"))
          recv <- conn.receiveStream.compile.toList
        } yield recv
      }
      .assertEquals(
        List(
          WSFrame.Text("bar"),
          WSFrame.Binary(ByteVector(3, 99, 12)),
          WSFrame.Text("foo"),
          WSFrame.Close(1000, "")
        )
      )
  }

  test("send and receive frames in high-level mode") {
    client()
      .connectHighLevel(WSRequest(server()))
      .use { conn =>
        for {
          _ <- conn.send(WSFrame.Binary(ByteVector(15, 2, 3)))
          _ <- conn.sendMany(List(WSFrame.Text("foo"), WSFrame.Text("bar")))
          recv <- conn.receiveStream.take(3).compile.toList
        } yield recv
      }
      .assertEquals(
        List(
          WSFrame.Binary(ByteVector(15, 2, 3)),
          WSFrame.Text("foo"),
          WSFrame.Text("bar")
        )
      )
  }

  test("group frames by their `last` attribute in high-level mode".ignore) {
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

  def echoRoutes(ws: WebSocketBuilder2[IO]): HttpRoutes[IO] =
    HttpRoutes.of[IO] { case _ -> Root / "echo" =>
      ws.build(identity)
    }

}
