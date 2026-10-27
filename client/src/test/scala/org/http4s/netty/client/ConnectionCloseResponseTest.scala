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
import cats.effect.Ref
import cats.effect.Resource
import com.comcast.ip4s._
import fs2.Chunk
import fs2.Stream
import fs2.io.net.Network
import fs2.io.net.Socket
import fs2.text
import org.http4s.Request
import org.http4s.Uri

import java.nio.charset.StandardCharsets
import scala.concurrent.duration._

/** A server that answers with `Connection: close` is telling the client that the connection must
  * not be reused (RFC 9112 §9.6). The client must therefore not return such a connection to its
  * pool.
  *
  * The server used here deliberately keeps the socket open after sending `Connection: close`, so
  * that any reuse by the client is observable as a second request arriving on the same connection
  * (instead of being masked by a racy "channel closed" failure).
  */
class ConnectionCloseResponseTest extends IOSuite {

  private def response(body: String): Chunk[Byte] = {
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    val head =
      s"HTTP/1.1 200 OK\r\nContent-Length: ${bytes.length}\r\nConnection: close\r\n\r\n"
    Chunk.array(head.getBytes(StandardCharsets.US_ASCII) ++ bytes)
  }

  /** Serves every HTTP/1.1 request (without body) on the connection, replying with
    * `conn=<connection number>;req=<request number on that connection>`.
    */
  private def handle(socket: Socket[IO], connId: Int): Stream[IO, Nothing] =
    socket.reads
      .through(text.utf8.decode)
      .through(text.lines)
      .filter(_.isEmpty) // an empty line terminates the request head
      .zipWithIndex
      .evalMap { case (_, reqIdx) =>
        socket.write(response(s"conn=$connId;req=${reqIdx + 1}"))
      }
      .drain

  private val connectionCloseServer: Resource[IO, (SocketAddress[IpAddress], Ref[IO, Int])] =
    for {
      accepted <- Resource.eval(Ref.of[IO, Int](0))
      serverSocket <- Network[IO].bind(SocketAddress(ip"127.0.0.1", port"0"))
      _ <- serverSocket.accept
        .evalMap(socket => accepted.updateAndGet(_ + 1).map(handle(socket, _)))
        .parJoinUnbounded
        .compile
        .drain
        .background
    } yield (serverSocket.address.asIpUnsafe, accepted)

  test("connection is not reused after server responds with Connection: close") {
    val resources = for {
      server <- connectionCloseServer
      client <- NettyClientBuilder[IO].resource
    } yield (server, client)

    resources.use { case ((address, accepted), client) =>
      val req = Request[IO](uri = Uri.unsafeFromString(s"http://$address/"))
      for {
        first <- client.expect[String](req).timeout(5.seconds)
        second <- client.expect[String](req).timeout(5.seconds)
        connections <- accepted.get
      } yield {
        assertEquals(first, "conn=1;req=1")
        assertEquals(
          second,
          "conn=2;req=1",
          "second request was sent on the connection the server asked to close")
        assertEquals(connections, 2)
      }
    }
  }
}
