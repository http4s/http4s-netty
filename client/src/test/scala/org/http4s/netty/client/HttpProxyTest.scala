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

import cats.syntax.all._
import cats.effect.{IO, Resource, Sync}
import com.comcast.ip4s._
import com.github.monkeywie.proxyee.server.HttpProxyServer
import org.http4s.Uri

import java.net.ServerSocket
import scala.compat.java8.FutureConverters._

class HttpProxyTest extends IOSuite {

  val httpbin = resourceFixture(HttpBinTest.httpBin, "httpbin")

  val proxy = resourceFixture(
    for {
      address <- Resource.eval(HttpProxyTest.randomSocketAddress[IO])
      _ <- Resource {
        val s = new HttpProxyServer()
        IO.fromFuture(
          IO(toScala(s.startAsync(address.host.toInetAddress.getHostAddress, address.port.value))))
          .as(s -> IO.blocking(s.close()))
      }
    } yield HttpProxy(
      Uri.Scheme.http,
      address.host,
      Some(address.port),
      IgnoredHosts.fromString("*.google.com").get,
      None),
    "proxy"
  )

  test("http GET via proxy") {
    NettyClientBuilder[IO]
      .withProxy(proxy())
      .resource
      .use { client =>
        val base = httpbin()
        client.expect[String](base / "get").map { s =>
          assert(s.nonEmpty)
        }
      }

  }
}

object HttpProxyTest {
  def randomSocketAddress[F[_]: Sync] = {
    def getLoopBack = Dns[F].loopback
    def randomPort = Sync[F].blocking {
      val s = new ServerSocket(0)
      s.close()
      Port.fromInt(s.getLocalPort).get
    }
    getLoopBack.flatMap(address => randomPort.map(SocketAddress(address, _)))
  }
}
