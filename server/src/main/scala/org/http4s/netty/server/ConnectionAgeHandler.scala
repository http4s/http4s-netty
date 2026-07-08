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

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration

/** Closes the connection after it has been open for longer than maxConnectionAge. The close is
  * deferred until the current request completes by firing a [[ConnectionAgeExpiredEvent]] user
  * event.
  */
private[server] class ConnectionAgeHandler(maxConnectionAge: Duration)
    extends ChannelInboundHandlerAdapter {

  private val logger = org.log4s.getLogger

  override def channelActive(ctx: ChannelHandlerContext): Unit = {
    if (maxConnectionAge.isFinite && maxConnectionAge.length > 0) {
      val jitteredDelay = addJitter(maxConnectionAge.toMillis)
      ctx
        .executor()
        .schedule(
          new Runnable {
            override def run(): Unit =
              if (ctx.channel().isOpen) {
                logger.trace(s"Connection age exceeded for ${ctx.channel().remoteAddress()}")
                void(ctx.fireUserEventTriggered(ConnectionAgeExpiredEvent))
              }
          },
          jitteredDelay,
          TimeUnit.MILLISECONDS
        )
    }
    super.channelActive(ctx)
  }

  /** Adds up to +/-10% jitter to avoid thundering herd */
  private def addJitter(millis: Long): Long = {
    val jitter = (millis * 0.1 * (Math.random() * 2 - 1)).toLong
    millis + jitter
  }
}

private[server] case object ConnectionAgeExpiredEvent
