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
import fs2.Stream
import munit.catseffect.IOFixture
import org.http4s._
import org.http4s.client.websocket._
import org.http4s.dsl.io._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame

import scala.concurrent.duration._

/** Releasing a `NettyWSClientBuilder` client while frames are still arriving must not hang.
  *
  * Every frame is handed over from the event loop with `dispatcher.unsafeRunSync`. If the
  * dispatcher is released before the event loop, a frame arriving in between is rejected by the
  * closing dispatcher and `unsafeRunSync` never returns: the event loop is parked,
  * `shutdownGracefully` never completes and the release of the client hangs forever.
  */
class NettyWSClientReleaseTest extends IOSuite {
  val server: IOFixture[Uri] = resourceFixture(
    EmberServerBuilder
      .default[IO]
      .withHttpWebSocketApp(floodRoutes(_).orNotFound)
      .withPort(port"0")
      .withShutdownTimeout(100.milli)
      .build
      .map(s => s.baseUri.copy(scheme = Uri.Scheme.unsafeFromString("ws").some) / "flood"),
    "server"
  )

  // a fresh client per round: the release of the client is what is under test
  private def connectReceiveOneAndRelease: IO[Unit] =
    NettyWSClientBuilder[IO].withNioTransport.resource
      .use(_.connect(WSRequest(server())).use(_.receive))
      .void

  test("releasing the client while the server keeps sending frames does not hang") {
    connectReceiveOneAndRelease
      .timeoutAndForget(15.seconds)
      .replicateA_(5)
  }

  /** Sends text frames as fast as it can, so frames are always in flight when the client is
    * released.
    */
  def floodRoutes(ws: WebSocketBuilder2[IO]): HttpRoutes[IO] =
    HttpRoutes.of[IO] { case GET -> Root / "flood" =>
      ws.build(
        send = Stream.constant(WebSocketFrame.Text("flood")).covary[IO],
        receive = _.drain
      )
    }
}
