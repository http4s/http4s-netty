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
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelPipeline
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpServerUpgradeHandler
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler
import io.netty.handler.codec.http2.CleartextHttp2ServerUpgradeHandler
import io.netty.handler.codec.http2.Http2CodecUtil
import io.netty.handler.codec.http2.Http2FrameCodecBuilder
import io.netty.handler.codec.http2.Http2MultiplexHandler
import io.netty.handler.codec.http2.Http2ServerUpgradeCodec
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec
import io.netty.handler.timeout.IdleStateHandler
import io.netty.util.AsciiString
import org.http4s.Response
import org.http4s.netty.HttpResource
import org.http4s.netty.void
import org.http4s.server.ServiceErrorHandler
import org.http4s.server.websocket.WebSocketBuilder2

private object NettyPipelineHelpers {

  private val idleHandlerName = "idle-handler"
  private val wsCompressionName = "websocket-compression"
  private val wsAggregatorName = "websocket-aggregator"
  private val streamsHandlerName = "serverStreamsHandler"
  private val http4sHandlerName = "http4s"
  private val h2cUpgradeCleanupName = "h2c-upgrade-cleanup"

  private val h1HandlerNames =
    Seq(
      idleHandlerName,
      wsCompressionName,
      wsAggregatorName,
      streamsHandlerName,
      http4sHandlerName,
      h2cUpgradeCleanupName)

  def buildCleartextPipeline[F[_]: Async](
      pipeline: ChannelPipeline,
      config: NegotiationHandler.Config,
      httpApp: WebSocketBuilder2[F] => HttpResource[F],
      serviceErrorHandler: ServiceErrorHandler[F],
      requestLineParseErrorHandler: Throwable => F[Response[F]],
      dispatcher: Dispatcher[F]): Unit = void {
    // Start with H1 autoRead setting. Will be switched to true on H2 upgrade or prior knowledge.
    pipeline.channel.config.setAutoRead(false)

    val httpCodec =
      new HttpServerCodec(config.maxInitialLineLength, config.maxHeaderSize, config.maxChunkSize)

    val upgradeCodecFactory: HttpServerUpgradeHandler.UpgradeCodecFactory =
      (protocol: CharSequence) =>
        if (AsciiString.contentEqualsIgnoreCase(
            Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME,
            protocol))
          new Http2ServerUpgradeCodec(
            Http2FrameCodecBuilder.forServer().build(),
            newH2MultiplexHandler(
              config,
              httpApp,
              serviceErrorHandler,
              requestLineParseErrorHandler,
              dispatcher)
          )
        else null

    val upgradeHandler = new HttpServerUpgradeHandler(httpCodec, upgradeCodecFactory)

    // Handler for H2 prior knowledge: cleans up H1 handlers and sets up H2 pipeline
    val h2PriorKnowledgeHandler = new ChannelInitializer[Channel] {
      override def initChannel(ch: Channel): Unit = {
        removeH1Handlers(ch.pipeline)
        buildHttp2Pipeline(
          ch.pipeline,
          config,
          httpApp,
          serviceErrorHandler,
          requestLineParseErrorHandler,
          dispatcher)
      }
    }

    pipeline.addLast(
      new CleartextHttp2ServerUpgradeHandler(httpCodec, upgradeHandler, h2PriorKnowledgeHandler))

    // Add H1 application handlers (used when connection is plain HTTP/1.1)
    addHttp4sHandlers(
      pipeline,
      config,
      httpApp,
      serviceErrorHandler,
      requestLineParseErrorHandler,
      dispatcher)

    // Cleanup handler for h2c upgrade path: removes H1 handlers after successful upgrade
    pipeline.addLast(
      h2cUpgradeCleanupName,
      new ChannelInboundHandlerAdapter {
        override def userEventTriggered(ctx: ChannelHandlerContext, evt: AnyRef): Unit =
          evt match {
            case upgrade: HttpServerUpgradeHandler.UpgradeEvent =>
              try {
                void(ctx.channel.config.setAutoRead(true))
                // removeH1Handlers also removes this cleanup handler
                removeH1Handlers(ctx.pipeline)
              } finally
                void(upgrade.release())
            case _ =>
              super.userEventTriggered(ctx, evt)
          }
      }
    )
  }

  def buildHttp2Pipeline[F[_]: Async](
      pipeline: ChannelPipeline,
      config: NegotiationHandler.Config,
      httpApp: WebSocketBuilder2[F] => HttpResource[F],
      serviceErrorHandler: ServiceErrorHandler[F],
      requestLineParseErrorHandler: Throwable => F[Response[F]],
      dispatcher: Dispatcher[F]): Unit = void {
    // H2, being a multiplexed protocol, needs to always be reading events in case
    // it needs to close a stream, etc. Flow control is provided by the protocol itself.
    pipeline.channel.config.setAutoRead(true)

    pipeline
      .addLast(
        Http2FrameCodecBuilder.forServer().build(),
        newH2MultiplexHandler(
          config,
          httpApp,
          serviceErrorHandler,
          requestLineParseErrorHandler,
          dispatcher)
      )
  }

  def buildHttp1Pipeline[F[_]: Async](
      pipeline: ChannelPipeline,
      config: NegotiationHandler.Config,
      httpApp: WebSocketBuilder2[F] => HttpResource[F],
      serviceErrorHandler: ServiceErrorHandler[F],
      requestLineParseErrorHandler: Throwable => F[Response[F]],
      dispatcher: Dispatcher[F]): Unit = void {
    // For HTTP/1.x pipelines the only backpressure we can exert is via the TCP
    // flow control mechanisms. That means we set auto-read to false so that we
    // can explicitly signal that we're ready for more data.
    pipeline.channel.config.setAutoRead(false)

    pipeline.addLast(
      "http-codec",
      new HttpServerCodec(config.maxInitialLineLength, config.maxHeaderSize, config.maxChunkSize))
    addHttp4sHandlers(
      pipeline,
      config,
      httpApp,
      serviceErrorHandler,
      requestLineParseErrorHandler,
      dispatcher)
  }

  private def newH2MultiplexHandler[F[_]: Async](
      config: NegotiationHandler.Config,
      httpApp: WebSocketBuilder2[F] => HttpResource[F],
      serviceErrorHandler: ServiceErrorHandler[F],
      requestLineParseErrorHandler: Throwable => F[Response[F]],
      dispatcher: Dispatcher[F]): Http2MultiplexHandler =
    new Http2MultiplexHandler(new ChannelInitializer[Channel] {
      override def initChannel(ch: Channel): Unit = {
        ch.pipeline.addLast(new Http2StreamFrameToHttpObjectCodec(true))
        addHttp4sHandlers(
          ch.pipeline,
          config,
          httpApp,
          serviceErrorHandler,
          requestLineParseErrorHandler,
          dispatcher)
      }
    })

  private def addHttp4sHandlers[F[_]: Async](
      pipeline: ChannelPipeline,
      config: NegotiationHandler.Config,
      httpApp: WebSocketBuilder2[F] => HttpResource[F],
      serviceErrorHandler: ServiceErrorHandler[F],
      requestLineParseErrorHandler: Throwable => F[Response[F]],
      dispatcher: Dispatcher[F]): Unit = void {

    if (config.idleTimeout.isFinite && config.idleTimeout.length > 0) {
      void(
        pipeline.addLast(
          idleHandlerName,
          new IdleStateHandler(0, 0, config.idleTimeout.length, config.idleTimeout.unit)))
    }

    if (config.wsCompression) {
      void(
        pipeline.addLast(
          wsCompressionName,
          new WebSocketServerCompressionHandler(config.wsMaxFrameLength)))
    }
    pipeline.addLast(wsAggregatorName, new WebSocketFrameAggregator(config.wsMaxFrameLength))
    pipeline.addLast(streamsHandlerName, new DirectStreamingServerHandler())
    pipeline.addLast(
      http4sHandlerName,
      Http4sNettyHandler
        .websocket(
          httpApp,
          serviceErrorHandler,
          requestLineParseErrorHandler,
          config.wsMaxFrameLength,
          dispatcher)
    )
  }

  private def removeH1Handlers(pipeline: ChannelPipeline): Unit =
    h1HandlerNames.foreach { name =>
      if (pipeline.get(name) != null) void(pipeline.remove(name))
    }
}
