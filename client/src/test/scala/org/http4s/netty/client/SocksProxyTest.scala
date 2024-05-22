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
import munit.catseffect.IOFixture
import org.bbottema.javasocksproxyserver.SyncSocksServer
import org.http4s.HttpRoutes
import org.http4s.Response
import org.http4s.Uri
import org.http4s.client.testkit.scaffold.ServerScaffold

class SocksProxyTest extends IOSuite {
  val server: IOFixture[Uri] = resourceFixture(
    ServerScaffold[IO](
      1,
      secure = false,
      HttpRoutes.pure(Response[IO]().withEntity("Hello from origin")))
      .map(_.servers.head.uri),
    "server")

  val socks: IOFixture[(Socks4, Socks5)] = resourceFixture(
    for {
      address <- Resource.eval(HttpProxyTest.randomSocketAddress[IO])
      _ <- Resource.make[IO, SyncSocksServer] {
        val s = new SyncSocksServer()
        IO.blocking(s.start(address.port.value)).as(s)
      }(s => IO.blocking(s.stop()).void)
    } yield Socks4(address.host, address.port, None) -> Socks5(
      address.host,
      address.port,
      None,
      None),
    "socks"
  )

  test("http GET via Socks 4 proxy") {
    NettyClientBuilder[IO]
      .withProxy(socks()._1)
      .resource
      .use { client =>
        val base = server()
        client.expect[String](base / "get").map { s =>
          assert(s.nonEmpty)
        }
      }
  }

  test("http GET via Socks 5 proxy") {
    NettyClientBuilder[IO]
      .withProxy(socks()._2)
      .resource
      .use { client =>
        val base = server()
        client.expect[String](base / "get").map { s =>
          assert(s.nonEmpty)
        }
      }

  }
}
