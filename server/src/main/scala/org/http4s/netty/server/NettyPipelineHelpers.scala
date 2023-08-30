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

import cats.effect.Async
import cats.effect.std.Dispatcher
import com.typesafe.netty.http.HttpStreamsServerHandler
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelPipeline
import io.netty.handler.codec.http.HttpRequestDecoder
import io.netty.handler.codec.http.HttpResponseEncoder
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler
import io.netty.handler.codec.http2.Http2FrameCodecBuilder
import io.netty.handler.codec.http2.Http2MultiplexHandler
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec
import io.netty.handler.timeout.IdleStateHandler
import org.http4s.HttpApp
import org.http4s.netty.void
import org.http4s.server.ServiceErrorHandler
import org.http4s.server.websocket.WebSocketBuilder2

private object NettyPipelineHelpers {

  def buildHttp2Pipeline[F[_]: Async](
      pipeline: ChannelPipeline,
      config: NegotiationHandler.Config,
      httpApp: WebSocketBuilder2[F] => HttpApp[F],
      serviceErrorHandler: ServiceErrorHandler[F],
      dispatcher: Dispatcher[F]): Unit = void {
    // H2, being a multiplexed protocol, needs to always be reading events in case
    // it needs to close a stream, etc. Flow control is provided by the protocol itself.
    pipeline.channel.config.setAutoRead(true)

    pipeline
      .addLast(
        Http2FrameCodecBuilder.forServer().build(),
        new Http2MultiplexHandler(new ChannelInitializer[Channel] {
          override def initChannel(ch: Channel): Unit = {
            ch.pipeline.addLast(new Http2StreamFrameToHttpObjectCodec(true))
            addHttp4sHandlers(ch.pipeline, config, httpApp, serviceErrorHandler, dispatcher)
          }
        })
      )
  }

  def buildHttp1Pipeline[F[_]: Async](
      pipeline: ChannelPipeline,
      config: NegotiationHandler.Config,
      httpApp: WebSocketBuilder2[F] => HttpApp[F],
      serviceErrorHandler: ServiceErrorHandler[F],
      dispatcher: Dispatcher[F]): Unit = void {
    // For HTTP/1.x pipelines the only backpressure we can exert is via the TCP
    // flow control mechanisms. That means we set auto-read to false so that we
    // can explicitly signal that we're ready for more data.
    pipeline.channel.config.setAutoRead(false)

    pipeline.addLast(
      "http-decoder",
      new HttpRequestDecoder(
        config.maxInitialLineLength,
        config.maxHeaderSize,
        config.maxChunkSize))
    pipeline.addLast("http-encoder", new HttpResponseEncoder())
    addHttp4sHandlers(pipeline, config, httpApp, serviceErrorHandler, dispatcher)
  }

  private[this] def addHttp4sHandlers[F[_]: Async](
      pipeline: ChannelPipeline,
      config: NegotiationHandler.Config,
      httpApp: WebSocketBuilder2[F] => HttpApp[F],
      serviceErrorHandler: ServiceErrorHandler[F],
      dispatcher: Dispatcher[F]): Unit = void {

    if (config.idleTimeout.isFinite && config.idleTimeout.length > 0) {
      void(
        pipeline.addLast(
          "idle-handler",
          new IdleStateHandler(0, 0, config.idleTimeout.length, config.idleTimeout.unit)))
    }

    if (config.wsCompression) {
      void(pipeline.addLast("websocket-compression", new WebSocketServerCompressionHandler))
    }
    pipeline.addLast("websocket-aggregator", new WebSocketFrameAggregator(config.wsMaxFrameLength))
    pipeline.addLast("serverStreamsHandler", new HttpStreamsServerHandler())
    pipeline.addLast(
      "http4s",
      Http4sNettyHandler
        .websocket(httpApp, serviceErrorHandler, config.wsMaxFrameLength, dispatcher)
    )
  }
}
