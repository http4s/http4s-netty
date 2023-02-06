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

package org.http4s.netty.client

import io.netty.bootstrap.Bootstrap
import io.netty.channel.ChannelOption
import io.netty.channel.MultithreadEventLoopGroup
import io.netty.channel.epoll.Epoll
import io.netty.channel.epoll.EpollEventLoopGroup
import io.netty.channel.epoll.EpollSocketChannel
import io.netty.channel.kqueue.KQueue
import io.netty.channel.kqueue.KQueueEventLoopGroup
import io.netty.channel.kqueue.KQueueSocketChannel
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.incubator.channel.uring.IOUring
import io.netty.incubator.channel.uring.IOUringEventLoopGroup
import io.netty.incubator.channel.uring.IOUringSocketChannel
import org.http4s.netty.NettyTransport
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.reflect.ClassTag

private[client] final case class EventLoopHolder[A <: SocketChannel](
    eventLoop: MultithreadEventLoopGroup)(implicit classTag: ClassTag[A]) {
  def runtimeClass: Class[A] = classTag.runtimeClass.asInstanceOf[Class[A]]

  def configure(bootstrap: Bootstrap): Bootstrap =
    bootstrap
      .group(eventLoop)
      .channel(runtimeClass)
      .option(ChannelOption.TCP_NODELAY, java.lang.Boolean.TRUE)
      .option(ChannelOption.SO_KEEPALIVE, java.lang.Boolean.TRUE)
}

private[client] object EventLoopHolder {
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  def fromTransport(
      transport: NettyTransport,
      eventLoopThreads: Int): EventLoopHolder[_ <: SocketChannel] =
    transport match {
      case NettyTransport.Nio =>
        EventLoopHolder[NioSocketChannel](new NioEventLoopGroup(eventLoopThreads))
      case NettyTransport.Native =>
        if (IOUring.isAvailable) {
          logger.info("Using IOUring")
          EventLoopHolder[IOUringSocketChannel](new IOUringEventLoopGroup(eventLoopThreads))
        } else if (Epoll.isAvailable) {
          logger.info("Using Epoll")
          EventLoopHolder[EpollSocketChannel](new EpollEventLoopGroup(eventLoopThreads))
        } else if (KQueue.isAvailable) {
          logger.info("Using KQueue")
          EventLoopHolder[KQueueSocketChannel](new KQueueEventLoopGroup(eventLoopThreads))
        } else {
          logger.info("Falling back to NIO EventLoopGroup")
          EventLoopHolder[NioSocketChannel](new NioEventLoopGroup(eventLoopThreads))
        }
    }
}
