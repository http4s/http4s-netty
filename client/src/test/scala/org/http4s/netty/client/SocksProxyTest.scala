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
import org.bbottema.javasocksproxyserver.SocksServer

class SocksProxyTest extends IOSuite {
  val httpBin = resourceFixture(HttpBinTest.httpBin, "httpbin")

  val socks = resourceFixture(
    for {
      blocker <- Blocker[IO]
      address <- Resource.eval(HttpProxyTest.randomSocketAddress[IO](blocker))
      _ <- Resource {
        val s = new SocksServer()
        blocker
          .blockOn(IO(s.start(address.port.value)))
          .map(_ => s -> blocker.blockOn(IO(s.stop())))
      }
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
        val base = httpBin()
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
        val base = httpBin()
        client.expect[String](base / "get").map { s =>
          assert(s.nonEmpty)
        }
      }

  }
}
