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
import cats.syntax.all._
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandler
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.DefaultHttpRequest
import io.netty.handler.codec.http.DefaultHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketFrame
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler
import munit.CatsEffectSuite
import org.http4s.Uri
import org.http4s.client.websocket.WSFrame
import org.http4s.client.websocket.WSRequest
import org.http4s.implicits._
import org.http4s.netty.client.NettyWSClientBuilder
import org.http4s.netty.void

import scala.concurrent.duration._

class WebSocketCompressionTest extends CatsEffectSuite {
  import WebSocketCompressionTest._

  test("default settings negotiate like netty's WebSocketServerCompressionHandler") {
    List(
      "permessage-deflate",
      "permessage-deflate; client_max_window_bits",
      "permessage-deflate; client_max_window_bits=10",
      "permessage-deflate; server_max_window_bits=10",
      "permessage-deflate; server_no_context_takeover",
      "permessage-deflate; client_no_context_takeover",
      "x-webkit-deflate-frame",
      "deflate-frame"
    ).foreach { offer =>
      assertEquals(
        negotiate(handler(WebSocketCompression.default), offer),
        negotiate(new WebSocketServerCompressionHandler(MaxAllocation), offer),
        offer)
    }
  }

  test("windowBits is announced and the compressor works with a custom window and memLevel") {
    val compression = WebSocketCompression.default.withWindowBits(12).withMemLevel(5)
    withHandshake(handler(compression), "permessage-deflate") { (channel, extensions) =>
      assertEquals(extensions, Some("permessage-deflate;server_max_window_bits=12"))
      val frame = send(channel, new TextWebSocketFrame(LargeMessage))
      assert(frame.compressed, frame)
      assert(frame.size < LargeMessage.length / 10, frame)
    }
  }

  test("allowServerWindowBits = false declines a server_max_window_bits offer") {
    val offer = "permessage-deflate; server_max_window_bits=10"
    assertEquals(
      negotiate(handler(WebSocketCompression.default.withAllowServerWindowBits(true)), offer),
      Some("permessage-deflate;server_max_window_bits=10"))
    assertEquals(
      negotiate(handler(WebSocketCompression.default.withAllowServerWindowBits(false)), offer),
      None)
  }

  test("compressionThreshold sends small messages uncompressed") {
    val compression = WebSocketCompression.default.withCompressionThreshold(100)
    withHandshake(handler(compression), "permessage-deflate") { (channel, _) =>
      val small = send(channel, new TextWebSocketFrame("hi"))
      assert(!small.compressed, small)
      assertEquals(small.size, 2)
      val large = send(channel, new TextWebSocketFrame(LargeMessage))
      assert(large.compressed, large)
    }
  }

  test("compressionThreshold never skips a continuation frame of a compressed message") {
    val compression = WebSocketCompression.default.withCompressionThreshold(100)
    withHandshake(handler(compression), "permessage-deflate") { (channel, _) =>
      val first = send(channel, new TextWebSocketFrame(false, 0, LargeMessage))
      assert(first.compressed, first)
      // below the threshold, but part of a message whose compression has started
      val continuation =
        send(channel, new ContinuationWebSocketFrame(true, 0, Unpooled.copiedBuffer("x".getBytes)))
      assertEquals(continuation.kind, "ContinuationWebSocketFrame")
    }
  }

  test("requiresJZlib covers every setting that can need a non-default window or memLevel") {
    val jdkZlib = WebSocketCompression.default.withAllowServerWindowBits(false)
    assert(!jdkZlib.requiresJZlib)
    assert(jdkZlib.withAllowServerWindowBits(true).requiresJZlib)
    assert(jdkZlib.withWindowBits(12).requiresJZlib)
    assert(jdkZlib.withMemLevel(5).requiresJZlib)
  }

  test("out-of-range settings are rejected") {
    val default = WebSocketCompression.default
    List(
      () => default.withCompressionLevel(10),
      () => default.withWindowBits(8),
      () => default.withMemLevel(0),
      () => default.withCompressionThreshold(-1)
    ).foreach(invalid => intercept[IllegalArgumentException](invalid()))
  }

  List(
    "compression level and threshold" ->
      WebSocketCompression.default.withCompressionLevel(1).withCompressionThreshold(64),
    "JZlib window and memory level" ->
      WebSocketCompression.default.withWindowBits(12).withMemLevel(5)
  ).foreach { case (name, compression) =>
    test(s"a server with custom $name echoes small and large messages") {
      val server = NettyServerBuilder[IO]
        .withHttpWebSocketApp(WebsocketTest.echoRoutes(_).orNotFound)
        .withWebSocketCompression(compression)
        .withNioTransport
        .withoutBanner
        .withShutdownTimeout(1.second)
        .bindAny()
        .resource
        .map(s => s.baseUri.copy(scheme = Some(Uri.Scheme.unsafeFromString("ws"))) / "echo")

      (server, NettyWSClientBuilder[IO].withWebSocketCompression.withNioTransport.resource).tupled
        .use { case (uri, client) =>
          client.connectHighLevel(WSRequest(uri)).use { conn =>
            conn.sendMany(List(WSFrame.Text("hi"), WSFrame.Text(LargeMessage))) *>
              conn.receiveStream.take(2).compile.toList
          }
        }
        .assertEquals(List(WSFrame.Text("hi"), WSFrame.Text(LargeMessage)))
    }
  }
}

object WebSocketCompressionTest {
  val MaxAllocation = 65536
  val LargeMessage: String = "hello, compression! " * 50

  final case class SentFrame(kind: String, compressed: Boolean, size: Int)

  def handler(compression: WebSocketCompression): ChannelHandler =
    NettyPipelineHelpers.newWebSocketCompressionHandler(compression, MaxAllocation)

  def negotiate(handler: ChannelHandler, offer: String): Option[String] =
    withHandshake(handler, offer)((_, extensions) => extensions)

  /** Runs a WebSocket upgrade offering `offer` through `handler`, then `f` with the channel and the
    * `Sec-WebSocket-Extensions` header of the 101 response.
    */
  def withHandshake[A](handler: ChannelHandler, offer: String)(
      f: (EmbeddedChannel, Option[String]) => A): A = {
    val channel = new EmbeddedChannel(handler)
    try {
      val request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/ws")
      void(
        request.headers
          .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE)
          .set(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET)
          .set(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, offer))
      void(channel.writeInbound(request))
      void(channel.readInbound[HttpRequest]())

      val response =
        new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.SWITCHING_PROTOCOLS)
      void(
        response.headers
          .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE)
          .set(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET))
      void(channel.writeOutbound(response))
      val written = channel.readOutbound[HttpResponse]()
      f(channel, Option(written.headers.get(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS)))
    } finally void(channel.finishAndReleaseAll())
  }

  /** Writes `frame` through the negotiated pipeline and describes the frame that comes out. */
  def send(channel: EmbeddedChannel, frame: WebSocketFrame): SentFrame = {
    void(channel.writeOutbound(frame))
    val out = channel.readOutbound[WebSocketFrame]()
    try
      SentFrame(out.getClass.getSimpleName, (out.rsv & 0x4) != 0, out.content.readableBytes)
    finally void(out.release())
  }
}
