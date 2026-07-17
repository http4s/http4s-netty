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
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder
import org.http4s.Response
import org.http4s.server.ServiceErrorHandler
import org.http4s.server.websocket.WebSocketBuilder2

import scala.annotation.nowarn

import java.util

/** Kept as a stub for binary compatibility (MiMa). */
@deprecated("No longer used", "0.7")
@nowarn("msg=is never used")
private class PriorKnowledgeDetectionHandler[F[_]: Async](
    config: NegotiationHandler.Config,
    httpApp: WebSocketBuilder2[F] => HttpResource[F],
    serviceErrorHandler: ServiceErrorHandler[F],
    requestLineParseErrorHandler: Throwable => F[Response[F]],
    dispatcher: Dispatcher[F]
) extends ByteToMessageDecoder {
  override protected def handlerRemoved0(ctx: ChannelHandlerContext): Unit =
    throw new UnsupportedOperationException()

  override def decode(ctx: ChannelHandlerContext, in: ByteBuf, out: util.List[AnyRef]): Unit =
    throw new UnsupportedOperationException()
}
