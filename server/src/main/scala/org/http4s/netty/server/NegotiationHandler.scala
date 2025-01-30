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
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.ssl.ApplicationProtocolNames
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler
import org.http4s.server.ServiceErrorHandler
import org.http4s.server.websocket.WebSocketBuilder2

import scala.concurrent.duration.Duration

private[server] class NegotiationHandler[F[_]: Async](
    config: NegotiationHandler.Config,
    httpApp: WebSocketBuilder2[F] => HttpResource[F],
    serviceErrorHandler: ServiceErrorHandler[F],
    dispatcher: Dispatcher[F]
) extends ApplicationProtocolNegotiationHandler(ApplicationProtocolNames.HTTP_1_1) {
  override def configurePipeline(ctx: ChannelHandlerContext, protocol: String): Unit =
    protocol match {
      case ApplicationProtocolNames.HTTP_2 =>
        NettyPipelineHelpers.buildHttp2Pipeline(
          ctx.pipeline,
          config,
          httpApp,
          serviceErrorHandler,
          dispatcher)

      case ApplicationProtocolNames.HTTP_1_1 =>
        NettyPipelineHelpers.buildHttp1Pipeline(
          ctx.pipeline,
          config,
          httpApp,
          serviceErrorHandler,
          dispatcher)

      case _ => throw new IllegalStateException(s"Protocol: $protocol not supported")
    }

}

object NegotiationHandler {
  final case class Config(
      maxInitialLineLength: Int,
      maxHeaderSize: Int,
      maxChunkSize: Int,
      idleTimeout: Duration,
      wsMaxFrameLength: Int,
      wsCompression: Boolean
  )
}
