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
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPipeline
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.http2.Http2CodecUtil
import org.http4s.HttpApp
import org.http4s.server.ServiceErrorHandler
import org.http4s.server.websocket.WebSocketBuilder
import org.log4s.getLogger

import java.util

private class PriorKnowledgeDetectionHandler[F[_]: Async](
    config: NegotiationHandler.Config,
    httpApp: WebSocketBuilder[F] => HttpApp[F],
    serviceErrorHandler: ServiceErrorHandler[F],
    dispatcher: Dispatcher[F]
) extends ByteToMessageDecoder {

  private[this] val logger = getLogger

  // The `connectionPrefaceBuf()` method returns a duplicate
  private[this] val preface = Http2CodecUtil.connectionPrefaceBuf()

  override protected def handlerRemoved0(ctx: ChannelHandlerContext): Unit = void {
    preface.release()
  }

  override def decode(ctx: ChannelHandlerContext, in: ByteBuf, out: util.List[AnyRef]): Unit = {
    logger.trace(s"decode: ctx = $ctx, in.readableBytes = ${in.readableBytes}")
    if (ByteBufUtil.equals(in, 0, preface, 0, Math.min(in.readableBytes, preface.readableBytes))) {
      // So far they're equal. Have we read the whole message? If so, it's H2 prior knowledge.
      // Otherwise we just don't have enough bytes yet to be sure.
      if (preface.readableBytes <= in.readableBytes) {
        initializeH2PriorKnowledge(ctx.pipeline)
      }
    } else {
      // Doesn't match the prior knowledge preface. Initialize H1 pipeline.
      initializeH1(ctx.pipeline)
    }
  }

  private[this] def initializeH2PriorKnowledge(pipeline: ChannelPipeline): Unit = void {
    logger.trace(s"initializing h2 pipeline. Current pipeline: $pipeline")
    NettyPipelineHelpers.buildHttp2Pipeline(
      pipeline,
      config,
      httpApp,
      serviceErrorHandler,
      dispatcher)
    pipeline.remove(this)
  }

  private[this] def initializeH1(pipeline: ChannelPipeline): Unit = void {
    logger.trace(s"initializing h1 pipeline. Current pipeline: $pipeline")
    NettyPipelineHelpers.buildHttp1Pipeline(
      pipeline,
      config,
      httpApp,
      serviceErrorHandler,
      dispatcher)
    pipeline.remove(this)
  }
}
