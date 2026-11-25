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

import io.netty.channel.group.ChannelGroup
import io.netty.channel.group.DefaultChannelGroup
import io.netty.util.concurrent.GlobalEventExecutor

import java.util.concurrent.atomic.AtomicBoolean

/** Shutdown state shared by all connections of one server instance. */
private[server] final class ServerShutdown {

  /** Accepted connections and their HTTP/2 stream channels.
    *
    * Stream channels are tracked so that waiting for the group to close also waits for every stream
    * to close: closing a stream cancels its in-flight request, and that has to happen before the
    * server releases its `Dispatcher`, otherwise the cancellation is lost and the release waits for
    * the request forever.
    */
  val channels: ChannelGroup = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE)

  private[this] val shuttingDown = new AtomicBoolean(false)

  def isShuttingDown: Boolean = shuttingDown.get

  /** From now on HTTP/1 responses close their connection instead of keeping it alive. */
  def begin(): Unit = shuttingDown.set(true)
}
