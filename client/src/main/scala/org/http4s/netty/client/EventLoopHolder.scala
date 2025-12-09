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
import io.netty.channel.ChannelOption
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.epoll.Epoll
import io.netty.channel.epoll.EpollIoHandler
import io.netty.channel.epoll.EpollSocketChannel
import io.netty.channel.kqueue.KQueue
import io.netty.channel.kqueue.KQueueIoHandler
import io.netty.channel.kqueue.KQueueSocketChannel
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.channel.uring.IoUring
import io.netty.channel.uring.IoUringIoHandler
import io.netty.channel.uring.IoUringSocketChannel
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.annotation.nowarn
import scala.reflect.ClassTag

private[client] final case class EventLoopHolder[A <: SocketChannel](
    eventLoop: MultiThreadIoEventLoopGroup)(implicit classTag: ClassTag[A]) {
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
        EventLoopHolder[NioSocketChannel](
          new MultiThreadIoEventLoopGroup(eventLoopThreads, NioIoHandler.newFactory()))
      case n: NettyTransport.Native =>
        selectTransport(n, eventLoopThreads).getOrElse(
          throw new IllegalStateException("No native transport available"))
    }

  @nowarn("cat=deprecation")
  def selectTransport(
      native: NettyTransport.Native,
      eventLoopThreads: Int): Option[EventLoopHolder[_ <: SocketChannel]] =
    native match {
      case NettyTransport.IOUring if IoUring.isAvailable =>
        logger.info("Using IOUring")
        Some(EventLoopHolder[IoUringSocketChannel](
          new MultiThreadIoEventLoopGroup(eventLoopThreads, IoUringIoHandler.newFactory())))
      case NettyTransport.Epoll if Epoll.isAvailable =>
        logger.info("Using Epoll")
        Some(EventLoopHolder[EpollSocketChannel](
          new MultiThreadIoEventLoopGroup(eventLoopThreads, EpollIoHandler.newFactory())))
      case NettyTransport.KQueue if KQueue.isAvailable =>
        logger.info("Using KQueue")
        Some(EventLoopHolder[KQueueSocketChannel](
          new MultiThreadIoEventLoopGroup(eventLoopThreads, KQueueIoHandler.newFactory())))
      case NettyTransport.Auto | NettyTransport.Native =>
        selectTransport(NettyTransport.IOUring, eventLoopThreads)
          .orElse(selectTransport(NettyTransport.Epoll, eventLoopThreads))
          .orElse(selectTransport(NettyTransport.KQueue, eventLoopThreads))
          .orElse {
            logger.info("Falling back to NIO EventLoopGroup")
            Some(EventLoopHolder[NioSocketChannel](
              new MultiThreadIoEventLoopGroup(eventLoopThreads, NioIoHandler.newFactory())))
          }
      case _ => None
    }
}
