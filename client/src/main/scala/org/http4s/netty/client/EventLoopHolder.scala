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
package client

import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelOption
import io.netty.channel.MultithreadEventLoopGroup
import io.netty.channel.epoll.Epoll
import io.netty.channel.epoll.EpollDatagramChannel
import io.netty.channel.epoll.EpollEventLoopGroup
import io.netty.channel.epoll.EpollSocketChannel
import io.netty.channel.kqueue.KQueue
import io.netty.channel.kqueue.KQueueDatagramChannel
import io.netty.channel.kqueue.KQueueEventLoopGroup
import io.netty.channel.kqueue.KQueueSocketChannel
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.DatagramChannel
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.incubator.channel.uring.IOUring
import io.netty.incubator.channel.uring.IOUringDatagramChannel
import io.netty.incubator.channel.uring.IOUringEventLoopGroup
import io.netty.incubator.channel.uring.IOUringSocketChannel
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.annotation.nowarn
import scala.reflect.ClassTag

private[client] final case class EventLoopHolder[A <: Channel](
    eventLoop: MultithreadEventLoopGroup)(implicit classTag: ClassTag[A]) {
  def runtimeClass: Class[A] = classTag.runtimeClass.asInstanceOf[Class[A]]

  def configure(bootstrap: Bootstrap): Bootstrap =
    bootstrap
      .group(eventLoop)
      .channel(runtimeClass)
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
      case n: NettyTransport.Native =>
        selectTransport(n, eventLoopThreads).getOrElse(
          throw new IllegalStateException("No native transport available"))
    }

  def fromUdpTransport(
      transport: NettyTransport,
      eventLoopThreads: Int): EventLoopHolder[_ <: DatagramChannel] =
    transport match {
      case NettyTransport.Nio =>
        EventLoopHolder[NioDatagramChannel](new NioEventLoopGroup(eventLoopThreads))
      case n: NettyTransport.Native =>
        selectUdpTransport(n, eventLoopThreads).getOrElse(
          throw new IllegalStateException("No native transport available"))
    }

  @nowarn("cat=deprecation")
  def selectTransport(
      native: NettyTransport.Native,
      eventLoopThreads: Int): Option[EventLoopHolder[_ <: SocketChannel]] =
    native match {
      case NettyTransport.IOUring if IOUring.isAvailable =>
        logger.info("Using IOUring")
        Some(EventLoopHolder[IOUringSocketChannel](new IOUringEventLoopGroup(eventLoopThreads)))
      case NettyTransport.Epoll if Epoll.isAvailable =>
        logger.info("Using Epoll")
        Some(EventLoopHolder[EpollSocketChannel](new EpollEventLoopGroup(eventLoopThreads)))
      case NettyTransport.KQueue if KQueue.isAvailable =>
        logger.info("Using KQueue")
        Some(EventLoopHolder[KQueueSocketChannel](new KQueueEventLoopGroup(eventLoopThreads)))
      case NettyTransport.Auto | NettyTransport.Native =>
        selectTransport(NettyTransport.IOUring, eventLoopThreads)
          .orElse(selectTransport(NettyTransport.Epoll, eventLoopThreads))
          .orElse(selectTransport(NettyTransport.KQueue, eventLoopThreads))
          .orElse {
            logger.info("Falling back to NIO EventLoopGroup")
            Some(EventLoopHolder[NioSocketChannel](new NioEventLoopGroup(eventLoopThreads)))
          }
      case _ => None
    }

  @nowarn("cat=deprecation")
  def selectUdpTransport(
      native: NettyTransport.Native,
      eventLoopThreads: Int): Option[EventLoopHolder[_ <: DatagramChannel]] =
    native match {
      case NettyTransport.IOUring if IOUring.isAvailable =>
        logger.info("Using IOUring")
        Some(EventLoopHolder[IOUringDatagramChannel](new IOUringEventLoopGroup(eventLoopThreads)))
      case NettyTransport.Epoll if Epoll.isAvailable =>
        logger.info("Using Epoll")
        Some(EventLoopHolder[EpollDatagramChannel](new EpollEventLoopGroup(eventLoopThreads)))
      case NettyTransport.KQueue if KQueue.isAvailable =>
        logger.info("Using KQueue")
        Some(EventLoopHolder[KQueueDatagramChannel](new KQueueEventLoopGroup(eventLoopThreads)))
      case NettyTransport.Auto | NettyTransport.Native =>
        selectUdpTransport(NettyTransport.IOUring, eventLoopThreads)
          .orElse(selectUdpTransport(NettyTransport.Epoll, eventLoopThreads))
          .orElse(selectUdpTransport(NettyTransport.KQueue, eventLoopThreads))
          .orElse {
            logger.info("Falling back to NIO EventLoopGroup")
            Some(EventLoopHolder[NioDatagramChannel](new NioEventLoopGroup(eventLoopThreads)))
          }
      case _ => None
    }
}
