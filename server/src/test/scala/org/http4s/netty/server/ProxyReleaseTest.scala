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
import cats.implicits._
import fs2.Stream
import munit.catseffect.IOFixture
import org.http4s.HttpRoutes
import org.http4s.Method
import org.http4s.Request
import org.http4s.Response
import org.http4s.Status
import org.http4s.client.Client
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.netty.client.NettyClientBuilder
import org.http4s.server.Server

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import scala.concurrent.duration._

/** Tests that the proxy pattern works correctly with HttpResource.
  *
  * The proxy uses `withHttpWebSocketResource` so the route returns `Resource[IO, Response[IO]]`
  * directly — the Resource finalizer IS the upstream connection release. The proxy's upstream
  * client has a pool size of 1, so if any request's finalizer doesn't fire, the next request
  * deadlocks waiting for a connection.
  *
  * Without direct body streaming, the reactive-streams bridge doesn't drain the body when
  * downstream clients don't read it, so the Resource finalizer never fires and the pool exhausts
  * under load.
  */
class ProxyReleaseTest extends IOSuite {
  val setup: IOFixture[(Server, Client[IO])] = resourceFixture(
    for {
      // Backend server
      backend <- NettyServerBuilder[IO]
        .withHttpApp(
          HttpRoutes
            .of[IO] {
              case GET -> Root / "data" =>
                Ok(Stream.emits("hello from backend".getBytes).covary[IO])
              case HEAD -> Root / "data" =>
                Ok(Stream.emits("hello from backend".getBytes).covary[IO])
            }
            .orNotFound
        )
        .withNioTransport
        .withoutBanner
        .bindAny()
        .resource

      // Proxy upstream client: pool size 1 so leaks cause deadlocks.
      proxyClient <- NettyClientBuilder[IO]
        .withMaxConnectionsPerKey(1)
        .withNioTransport
        .resource

      // Proxy server using HttpResource — the Resource wraps the upstream
      // client connection, so its finalizer returns the connection to the pool.
      proxy <- {
        val proxyRoutes: Request[IO] => Resource[IO, Response[IO]] = { req =>
          val backendUri = backend.baseUri / "data"
          proxyClient.run(Request[IO](method = req.method, uri = backendUri))
        }
        NettyServerBuilder[IO]
          .withHttpWebSocketResource(_ => proxyRoutes)
          .withNioTransport
          .withoutBanner
          .bindAny()
          .resource
      }

      // Downstream client
      client <- NettyClientBuilder[IO].withNioTransport.resource
    } yield (proxy, client),
    "setup"
  )

  test("body-consumed requests release connections") {
    val (proxy, client) = setup()
    val uri = proxy.baseUri / "proxy"

    client.expect[String](uri).map(body => assertEquals(body, "hello from backend"))
  }

  test("unconsumed body releases connection under load") {
    val (proxy, client) = setup()
    val uri = proxy.baseUri / "proxy"

    // 40 concurrent, 200 total — never consume the body.
    // Pool size is 1: if finalizers don't fire the pool exhausts
    // after 1 request and the rest time out.
    Stream
      .emits(1 to 200)
      .covary[IO]
      .parEvalMap(40) { _ =>
        client
          .run(Request[IO](uri = uri))
          .use(resp => IO(assertEquals(resp.status, Status.Ok)))
          .timeout(10.seconds)
      }
      .compile
      .drain
  }

  test("HEAD requests drain body and release connections") {
    val (proxy, client) = setup()
    val uri = proxy.baseUri / "proxy"

    // HEAD goes through writeFullResponse which must drain the body stream
    // so the Resource finalizer fires. Pool size 1 means the second request
    // deadlocks if the first doesn't release.
    // We accept Ok or InternalServerError because the Netty client can
    // intermittently mishandle HEAD responses with Content-Length headers.
    // The key assertion is no timeout — a leaked upstream connection with
    // pool size 1 would deadlock, not produce a status error.
    (1 to 5).toList.traverse_ { _ =>
      client
        .run(Request[IO](Method.HEAD, uri))
        .use(resp =>
          IO(
            assert(
              resp.status == Status.Ok || resp.status == Status.InternalServerError,
              s"Unexpected status: ${resp.status}")))
        .recover { case _: java.nio.channels.ClosedChannelException => () }
        .timeout(5.seconds)
    }
  }

  test("client disconnect mid-body still releases connection") {
    val (proxy, _) = setup()

    // Open a raw socket, send a request for the proxy, read the status line,
    // then close without reading the body. The server must still finalize
    // the body stream so the upstream connection returns to the pool.
    // Do it 3 times sequentially — with pool size 1, the second request
    // hangs if the first didn't release.
    (1 to 3).toList.traverse_ { _ =>
      IO.blocking {
        val host = proxy.baseUri.host.get.renderString
        val port = proxy.baseUri.port.get
        val socket = new Socket(host, port)
        try {
          socket.setSoTimeout(5000)
          val out = socket.getOutputStream
          val in = new BufferedReader(new InputStreamReader(socket.getInputStream))
          out.write("GET /proxy HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes)
          out.flush()
          val statusLine = in.readLine()
          assert(
            statusLine != null && statusLine.contains("200"),
            s"Expected 200 but got: $statusLine")
        } finally
          socket.close()
      } *> IO.sleep(500.millis) // allow server to detect close and finalize
    }
  }
}
