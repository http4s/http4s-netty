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
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelPipeline
import io.netty.channel.ChannelPromise
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http2.Http2SecurityUtil
import io.netty.handler.ssl.ApplicationProtocolConfig
import io.netty.handler.ssl.ApplicationProtocolConfig.Protocol
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior
import io.netty.handler.ssl.ApplicationProtocolNames
import io.netty.handler.ssl.SslProvider
import io.netty.handler.ssl.SupportedCipherSuiteFilter
import munit.CatsEffectSuite
import org.http4s.HttpApp
import org.http4s.HttpRoutes
import org.http4s.Uri
import org.http4s.client.websocket.WSFrame
import org.http4s.client.websocket.WSRequest
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.netty.client.NettyWSClientBuilder
import org.http4s.netty.void
import org.http4s.server.Server
import org.typelevel.ci._

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.Promise
import scala.concurrent.duration._

class PipelineCustomizerTest extends CatsEffectSuite {
  import PipelineCustomizerTest._

  test("http1 is invoked on cleartext HTTP/1.1 connections after the http4s handlers") {
    val invocations = new ConcurrentLinkedQueue[List[String]]()
    val requestUris = new ConcurrentLinkedQueue[String]()
    val customizer = http1Customizer { pipeline =>
      void(invocations.add(names(pipeline)))
      void(
        pipeline
          .addBefore("http4s", "test-request-observer", new RequestObserver(requestUris)))
    }
    serverResource(ServerTest.routes, customizer).use { server =>
      http1Get(server.baseUri / "simple").map { body =>
        assertEquals(body, "simple path")
        assertEquals(invocations.size, 1)
        val handlerNames = invocations.peek()
        assert(handlerNames.contains("http4s"), handlerNames)
        // names() ends with the tail context; http1 runs after the whole H1 assembly
        assertEquals(handlerNames.init.last, "h2c-upgrade-cleanup")
        assertEquals(requestUris.toArray.toList, List[AnyRef]("/simple"))
      }
    }
  }

  test("http1 is invoked on TLS connections negotiating http/1.1 but not h2") {
    val invocations = new ConcurrentLinkedQueue[List[String]]()
    val customizer = http1Customizer(pipeline => void(invocations.add(names(pipeline))))
    val routes = HttpRoutes
      .of[IO] { case req @ GET -> Root / "protocol" =>
        Ok(req.headers.get(ci"x-http2-stream-id").fold("h1")(_ => "h2"))
      }
      .orNotFound
    alpnServerResource(routes, customizer).use { server =>
      val uri = server.baseUri / "protocol"
      for {
        h1 <- tlsGet(uri, HttpClient.Version.HTTP_1_1)
        h2 <- tlsGet(uri, HttpClient.Version.HTTP_2)
      } yield {
        assertEquals(h1, "h1")
        assertEquals(h2, "h2")
        assertEquals(invocations.size, 1)
        assert(invocations.peek().contains("http4s"), invocations.peek())
      }
    }
  }

  test("http1 handlers around the WebSocket compression handler see compressed frames") {
    val wire = new ConcurrentLinkedQueue[ObservedFrame]()
    val payload = new ConcurrentLinkedQueue[ObservedFrame]()
    val customizer = http1Customizer { pipeline =>
      void(
        pipeline
          .addBefore("websocket-compression", "test-wire-frames", new FrameObserver(wire)))
      void(
        pipeline
          .addAfter("websocket-compression", "test-payload-frames", new FrameObserver(payload)))
    }
    val message = "hello, compression! " * 50
    val serverUri = NettyServerBuilder[IO]
      .withHttpWebSocketApp(WebsocketTest.echoRoutes(_).orNotFound)
      .withWebSocketCompression
      .withPipelineCustomizer(customizer)
      .withNioTransport
      .withoutBanner
      .withShutdownTimeout(1.second)
      .bindAny()
      .resource
      .map(s => s.baseUri.copy(scheme = Some(Uri.Scheme.unsafeFromString("ws"))) / "echo")

    (serverUri, NettyWSClientBuilder[IO].withWebSocketCompression.withNioTransport.resource).tupled
      .use { case (uri, client) =>
        client.connectHighLevel(WSRequest(uri)).use { conn =>
          conn.send(WSFrame.Text(message)) *> conn.receive
        }
      }
      .map { echoed =>
        assertEquals(echoed, Some(WSFrame.Text(message)))
        val size = message.getBytes("UTF-8").length
        for (direction <- List(In, Out)) {
          val wireFrames = wire.toArray(Array.empty[ObservedFrame]).filter(_.direction == direction)
          val payloadFrames =
            payload.toArray(Array.empty[ObservedFrame]).filter(_.direction == direction)
          assertEquals(payloadFrames.map(_.size).toList, List(size), direction)
          assertEquals(wireFrames.length, 1, direction)
          assert(wireFrames.forall(_.rsv1), s"$direction wire frame is not compressed")
          assert(wireFrames.forall(_.size < size / 10), wireFrames.toList)
        }
      }
  }

  test("http1 handlers are removed on an h2c upgrade") {
    val switch = new SwitchObserver
    serverResource(ServerTest.routes, switch.customizer).use { server =>
      rawSocket(server).use { socket =>
        IO.blocking {
          val addr = server.address
          val writer = new PrintWriter(socket.getOutputStream, true)
          writer.print(
            "GET /simple HTTP/1.1\r\n" +
              s"Host: ${addr.getHostName}:${addr.getPort}\r\n" +
              "Connection: Upgrade, HTTP2-Settings\r\n" +
              "Upgrade: h2c\r\n" +
              "HTTP2-Settings: AAMAAABkAAQBAAAAAAIAAAAA\r\n" +
              "\r\n"
          )
          writer.flush()
          val statusLine =
            new BufferedReader(new InputStreamReader(socket.getInputStream)).readLine()
          assert(statusLine.contains("101"), s"Expected 101 but got: $statusLine")
        } *> switch.assertSwitched
      }
    }
  }

  test("http1 handlers are removed on HTTP/2 prior knowledge") {
    val switch = new SwitchObserver
    serverResource(ServerTest.routes, switch.customizer).use { server =>
      rawSocket(server).use { socket =>
        IO.blocking {
          val out = socket.getOutputStream
          out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes("US-ASCII"))
          // empty SETTINGS frame
          out.write(Array[Byte](0, 0, 0, 0x04, 0, 0, 0, 0, 0))
          out.flush()
        } *> switch.assertSwitched
      }
    }
  }

  test("an exception thrown in http1 closes the connection") {
    val customizer = http1Customizer(_ => throw new RuntimeException("failure"))
    serverResource(ServerTest.routes, customizer).use { server =>
      rawSocket(server).use { socket =>
        IO.blocking {
          val writer = new PrintWriter(socket.getOutputStream, true)
          writer.print("GET /simple HTTP/1.1\r\nHost: localhost\r\n\r\n")
          writer.flush()
          socket.getInputStream.read()
        }.attempt
          .map {
            case Right(byte) => assertEquals(byte, -1)
            case Left(e) => assert(!e.isInstanceOf[java.net.SocketTimeoutException], e)
          }
      }
    }
  }

  test("combine applies both customizers in order") {
    def adding(name: String) =
      http1Customizer(p => void(p.addLast(name, new ChannelInboundHandlerAdapter)))
    val combined = PipelineCustomizer.combine(adding("a"), adding("b"))
    val channel = new EmbeddedChannel()
    try {
      combined.http1(channel.pipeline)
      assertEquals(
        names(channel.pipeline).filterNot(_.startsWith("DefaultChannelPipeline$")),
        List("a", "b"))
    } finally void(channel.finishAndReleaseAll())
  }

  private def serverResource(
      app: HttpApp[IO],
      customizer: PipelineCustomizer): Resource[IO, Server] =
    NettyServerBuilder[IO]
      .withHttpApp(app)
      .withPipelineCustomizer(customizer)
      .withNioTransport
      .withEventLoopThreads(1)
      .withShutdownTimeout(1.second)
      .withoutBanner
      .bindAny()
      .resource

  private def alpnServerResource(
      app: HttpApp[IO],
      customizer: PipelineCustomizer): Resource[IO, Server] =
    NettyServerBuilder[IO]
      .withHttpApp(app)
      .withPipelineCustomizer(customizer)
      .withSslContext(
        SslServerTest.sslContextForServer
          .sslProvider(SslProvider.JDK)
          .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
          .applicationProtocolConfig(new ApplicationProtocolConfig(
            Protocol.ALPN,
            SelectorFailureBehavior.NO_ADVERTISE,
            SelectedListenerFailureBehavior.ACCEPT,
            ApplicationProtocolNames.HTTP_2,
            ApplicationProtocolNames.HTTP_1_1
          ))
          .build())
      .withNioTransport
      .withShutdownTimeout(1.second)
      .withoutBanner
      .bindAny()
      .resource

  private def http1Get(uri: Uri): IO[String] =
    send(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(), uri)

  private def tlsGet(uri: Uri, version: HttpClient.Version): IO[String] =
    send(
      HttpClient
        .newBuilder()
        .version(version)
        .sslContext(SslServerTest.sslContextForClient)
        .build(),
      uri)

  private def send(client: HttpClient, uri: Uri): IO[String] =
    IO.fromCompletableFuture(IO {
      client.sendAsync(
        java.net.http.HttpRequest
          .newBuilder(URI.create(uri.renderString))
          .method("GET", BodyPublishers.noBody())
          .build(),
        BodyHandlers.ofString())
    }).map(_.body())

  private def rawSocket(server: Server): Resource[IO, Socket] =
    Resource.fromAutoCloseable(IO.blocking {
      val socket = new Socket(server.address.getHostName, server.address.getPort)
      socket.setSoTimeout(5000)
      socket
    })
}

object PipelineCustomizerTest {
  sealed trait Direction
  case object In extends Direction
  case object Out extends Direction

  final case class ObservedFrame(direction: Direction, rsv1: Boolean, size: Int)

  def http1Customizer(f: ChannelPipeline => Unit): PipelineCustomizer =
    new PipelineCustomizer {
      override def http1(pipeline: ChannelPipeline): Unit = f(pipeline)
    }

  def names(pipeline: ChannelPipeline): List[String] = {
    val builder = List.newBuilder[String]
    pipeline.names().forEach(name => void(builder += name))
    builder.result()
  }

  /** Watches a cleartext connection switching to HTTP/2: the http1 handler must be removed while
    * the connection is still open.
    */
  final class SwitchObserver {
    private val http1Removed = Promise[Boolean]()

    val customizer: PipelineCustomizer = http1Customizer(pipeline =>
      void(
        pipeline.addBefore(
          "http4s",
          "test-http1-observer",
          new ChannelInboundHandlerAdapter {
            override def handlerRemoved(ctx: ChannelHandlerContext): Unit =
              void(http1Removed.trySuccess(ctx.channel.isActive))
          }
        )))

    def assertSwitched: IO[Unit] =
      IO.fromFutureCancelable(IO((http1Removed.future, IO.unit)))
        .timeout(5.seconds)
        .map(assert(_, "http1 handler was removed only when the connection closed"))
  }

  final class RequestObserver(uris: ConcurrentLinkedQueue[String])
      extends ChannelInboundHandlerAdapter {
    override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef): Unit = {
      msg match {
        case request: HttpRequest => void(uris.add(request.uri))
        case _ => ()
      }
      void(ctx.fireChannelRead(msg))
    }
  }

  final class FrameObserver(frames: ConcurrentLinkedQueue[ObservedFrame])
      extends ChannelDuplexHandler {
    override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef): Unit = {
      observe(In, msg)
      void(ctx.fireChannelRead(msg))
    }

    override def write(ctx: ChannelHandlerContext, msg: AnyRef, promise: ChannelPromise): Unit = {
      observe(Out, msg)
      void(ctx.write(msg, promise))
    }

    private def observe(direction: Direction, msg: AnyRef): Unit = msg match {
      case frame: TextWebSocketFrame =>
        void(
          frames.add(ObservedFrame(direction, (frame.rsv & 0x4) != 0, frame.content.readableBytes)))
      case _ => ()
    }
  }
}
