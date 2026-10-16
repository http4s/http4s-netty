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

import io.netty.channel.ChannelPipeline
import org.http4s.netty.void

/** Customizes the netty channel pipelines of a server, e.g. to install instrumentation, filtering
  * or protocol handlers.
  *
  * Each method corresponds to a phase of a connection, and the handlers added in a phase live as
  * long as that phase. All methods do nothing by default: override the ones you need.
  *
  * The methods run on the channel's event loop while the pipeline is being assembled: they must not
  * block, and an exception thrown from them closes the connection.
  *
  * http4s-netty's own handlers, their names and their order are internal and may change between
  * releases: positioning relative to them, removing or replacing them is at your own risk.
  *
  * @see
  *   [[NettyServerBuilder.withPipelineCustomizer]]
  */
abstract class PipelineCustomizer {

  /** Invoked once per connection that can serve HTTP/1.1: every cleartext connection (when it is
    * accepted, before the protocol is known) and every TLS connection that negotiated `http/1.1`
    * via ALPN. It is not invoked for HTTP/2. It runs after http4s-netty has installed its own
    * handlers, as the last step of the pipeline assembly.
    *
    * Handlers added here stay for the lifetime of the connection, including after a WebSocket
    * upgrade, when HTTP messages give way to WebSocket frames. The exception is a cleartext
    * connection that switches to HTTP/2 (h2c upgrade or prior knowledge): every handler added here
    * is then removed together with the HTTP/1.1 handlers.
    *
    * The h2c upgrade is completed by a user event passing through http4s-netty's handlers, so
    * handlers placed among them must pass on user events they don't handle
    * (`ctx.fireUserEventTriggered`).
    */
  def http1(pipeline: ChannelPipeline): Unit = void(pipeline)
}

object PipelineCustomizer {

  /** A customizer that leaves all pipelines unchanged. */
  val empty: PipelineCustomizer = new PipelineCustomizer {}

  /** Applies `first`, then `second`, in every phase. */
  def combine(first: PipelineCustomizer, second: PipelineCustomizer): PipelineCustomizer =
    new PipelineCustomizer {
      override def http1(pipeline: ChannelPipeline): Unit = {
        first.http1(pipeline)
        second.http1(pipeline)
      }
    }
}
