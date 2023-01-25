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

package org.http4s.netty
package server

import cats.effect.Async
import cats.effect.std.Dispatcher
import com.typesafe.netty.http.HttpStreamsServerHandler
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelPipeline
import io.netty.handler.codec.http.HttpRequestDecoder
import io.netty.handler.codec.http.HttpResponseEncoder
import io.netty.handler.codec.http2.Http2FrameCodecBuilder
import io.netty.handler.codec.http2.Http2MultiplexHandler
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec
import io.netty.handler.ssl.ApplicationProtocolNames
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler
import io.netty.handler.timeout.IdleStateHandler
import org.http4s.HttpApp
import org.http4s.server.ServiceErrorHandler
import org.http4s.server.websocket.WebSocketBuilder2

import scala.concurrent.duration.Duration

private[server] class NegotiationHandler[F[_]: Async](
    config: NegotiationHandler.Config,
    httpApp: WebSocketBuilder2[F] => HttpApp[F],
    serviceErrorHandler: ServiceErrorHandler[F],
    dispatcher: Dispatcher[F]
) extends ApplicationProtocolNegotiationHandler(ApplicationProtocolNames.HTTP_1_1) {
  override def configurePipeline(ctx: ChannelHandlerContext, protocol: String): Unit =
    protocol match {
      case ApplicationProtocolNames.HTTP_2 =>
        ctx
          .pipeline()
          .addLast(
            Http2FrameCodecBuilder.forServer().build(),
            new Http2MultiplexHandler(new ChannelInitializer[Channel] {
              override def initChannel(ch: Channel): Unit = {
                ch.pipeline().addLast(new Http2StreamFrameToHttpObjectCodec(true))
                addToPipeline(ch.pipeline(), http1 = false)
              }
            })
          )
        ()
      case ApplicationProtocolNames.HTTP_1_1 =>
        val pipeline = ctx.pipeline()
        addToPipeline(pipeline, http1 = true)
      case _ => throw new IllegalStateException(s"Protocol: $protocol not supported")
    }

  def addToPipeline(pipeline: ChannelPipeline, http1: Boolean): Unit = void {
    if (http1) {
      pipeline.addLast(
        "http-decoder",
        new HttpRequestDecoder(
          config.maxInitialLineLength,
          config.maxHeaderSize,
          config.maxChunkSize))
      pipeline.addLast("http-encoder", new HttpResponseEncoder())
    }

    if (config.idleTimeout.isFinite && config.idleTimeout.length > 0)
      pipeline.addLast(
        "idle-handler",
        new IdleStateHandler(0, 0, config.idleTimeout.length, config.idleTimeout.unit))
    pipeline
      .addLast("serverStreamsHandler", new HttpStreamsServerHandler())
      .addLast(
        "http4s",
        Http4sNettyHandler
          .websocket(httpApp, serviceErrorHandler, config.wsMaxFrameLength, dispatcher)
      )
  }
}

object NegotiationHandler {
  final case class Config(
      maxInitialLineLength: Int,
      maxHeaderSize: Int,
      maxChunkSize: Int,
      idleTimeout: Duration,
      wsMaxFrameLength: Int
  )
}
