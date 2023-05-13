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
import cats.effect.Resource
import cats.effect.Sync
import cats.effect.kernel.Async
import cats.syntax.all._
import com.comcast.ip4s._
import com.github.monkeywie.proxyee.server.HttpProxyServer
import munit.catseffect.IOFixture
import org.http4s.HttpRoutes
import org.http4s.Response
import org.http4s.Uri
import org.http4s.client.testkit.scaffold.ServerScaffold

import java.net.ServerSocket
import scala.compat.java8.FutureConverters._

class HttpProxyTest extends IOSuite {

  val server: IOFixture[Uri] = resourceFixture(
    ServerScaffold[IO](1, false, HttpRoutes.pure(Response[IO]().withEntity("Hello from origin")))
      .map(_.servers.head.uri),
    "server")

  val proxy: IOFixture[HttpProxy] = resourceFixture(
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
        val base = server()
        client.expect[String](base / "get").map { s =>
          assert(s.nonEmpty)
        }
      }

  }
}

object HttpProxyTest {
  def randomSocketAddress[F[_]: Async]: F[SocketAddress[IpAddress]] = {
    def getLoopBack = Dns.forAsync[F].loopback
    def randomPort = Sync[F].blocking {
      val s = new ServerSocket(0)
      s.close()
      Port.fromInt(s.getLocalPort).get
    }
    getLoopBack.flatMap(address => randomPort.map(SocketAddress(address, _)))
  }
}
