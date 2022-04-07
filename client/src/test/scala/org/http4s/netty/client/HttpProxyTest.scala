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

import cats.effect.{Blocker, IO, Resource}
import com.comcast.ip4s._
import com.github.monkeywie.proxyee.server.HttpProxyServer
import org.gaul.httpbin.HttpBin
import org.http4s.Uri

import java.net.{ServerSocket, URI}
import scala.jdk.FutureConverters._

class HttpProxyTest extends IOSuite {
  def getLoopBack(implicit blocker: Blocker) = Dns[IO].loopback
  def uriFrom(bin: HttpBin) = Uri.unsafeFromString(s"http://localhost:${bin.getPort}")
  def randomPort(blocker: Blocker) = blocker.blockOn(IO {
    val s = new ServerSocket(0)
    s.close()
    Port.fromInt(s.getLocalPort).get
  })
  case class Server(baseUri: Uri, proxy: HttpProxy)

  val server = resourceFixture(
    for {
      blocker <- Blocker[IO]
      bin <- Resource(IO {
        val bin = new HttpBin(URI.create("http://localhost:0"))
        bin.start()
        bin -> IO(bin.stop())
      })
      address <- Resource.eval(
        getLoopBack(blocker).flatMap(loop => randomPort(blocker).map(SocketAddress(loop, _))))
      _ <- Resource {
        val s = new HttpProxyServer()
        IO.fromFuture(
          IO(s.startAsync(address.host.toInetAddress.getHostAddress, address.port.value).asScala))
          .as(s -> blocker.blockOn(IO(s.close())))
      }
    } yield Server(
      uriFrom(bin),
      HttpProxy(
        Uri.Scheme.http,
        address.host,
        Some(address.port),
        IgnoredHosts.fromString("*.google.com").get,
        None)),
    "server"
  )

  test("http GET via proxy") {
    NettyClientBuilder[IO]
      .withProxy(server().proxy)
      .resource
      .use { client =>
        println(server())
        val base = server().baseUri
        client.expect[String](base / "get").map { s =>
          println(s)
          assert(s.nonEmpty)
        }
      }

  }
}
