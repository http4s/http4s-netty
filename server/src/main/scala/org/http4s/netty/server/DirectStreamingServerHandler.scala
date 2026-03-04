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

import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import io.netty.handler.codec.http._
import org.playframework.netty.http.HttpStreamsServerHandler
import org.playframework.netty.http.StreamedHttpMessage
import org.playframework.netty.http.WebSocketHttpResponse

/** Extends [[HttpStreamsServerHandler]] to support direct body streaming.
  *
  * The parent class handles [[FullHttpMessage]] and [[StreamedHttpMessage]] on the outbound path
  * but silently drops plain [[DefaultHttpResponse]] (headers-only) messages because its
  * `unbufferedWrite` has no branch for them. This subclass intercepts plain [[HttpResponse]] writes
  * to enable a direct streaming pattern: headers, body chunks ([[HttpContent]]), and
  * [[LastHttpContent]] are written separately from the handler's fiber rather than bridging through
  * reactive streams. This ensures body stream finalizers fire reliably.
  *
  * Messages that the parent already handles ([[FullHttpMessage]], [[StreamedHttpMessage]] including
  * WebSocket upgrades) are delegated unchanged.
  *
  * Note: This class calls the protected `sentOutMessage(ctx)` method from
  * [[HttpStreamsServerHandler]] to keep the parent's request/response bookkeeping correct. This is
  * an internal API of the parent class.
  */
private[server] class DirectStreamingServerHandler extends HttpStreamsServerHandler {

  // Whether we are currently in the direct streaming state (between headers and LastHttpContent).
  private[this] var directStreaming: Boolean = false

  // Whether the channel should be closed after the current direct-streamed response completes.
  private[this] var closeAfterResponse: Boolean = false

  // Protocol version of the most recent inbound request, used for keep-alive default logic.
  private[this] var lastRequestVersion: HttpVersion = HttpVersion.HTTP_1_1

  override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef): Unit = {
    msg match {
      case req: HttpRequest => lastRequestVersion = req.protocolVersion()
      case _ =>
    }
    super.channelRead(ctx, msg)
  }

  override def write(ctx: ChannelHandlerContext, msg: AnyRef, promise: ChannelPromise): Unit =
    if (directStreaming)
      msg match {
        case _: LastHttpContent =>
          directStreaming = false
          val doClose = closeAfterResponse
          closeAfterResponse = false
          ctx.write(msg, promise)
          promise.addListener { (_: ChannelFuture) =>
            sentOutMessage(ctx)
            if (doClose) { val _ = ctx.close() }
          }
          ()

        case _ =>
          val _ = ctx.write(msg, promise)
      }
    else
      msg match {
        case response: HttpResponse
            if !response.isInstanceOf[FullHttpMessage] &&
              !response.isInstanceOf[StreamedHttpMessage] &&
              !response.isInstanceOf[WebSocketHttpResponse] =>
          // Plain headers-only HttpResponse: enter direct streaming mode.
          directStreaming = true
          closeAfterResponse = shouldClose(response)
          val _ = ctx.write(msg, promise)

        case _ =>
          // FullHttpMessage, StreamedHttpMessage, WebSocket, or non-HttpResponse:
          // delegate to the parent's reactive streams handling.
          super.write(ctx, msg, promise)
      }

  /** Determine whether the connection should be closed after this response, mirroring the logic
    * from [[HttpStreamsServerHandler]]'s `unbufferedWrite`.
    */
  private[this] def shouldClose(response: HttpResponse): Boolean = {
    val connection = response.headers().get(HttpHeaderNames.CONNECTION)
    if (lastRequestVersion.isKeepAliveDefault)
      "close".equalsIgnoreCase(connection)
    else
      !"keep-alive".equalsIgnoreCase(connection)
  }
}
