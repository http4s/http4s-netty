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

import cats.effect.Async
import cats.effect.Resource
import cats.effect.std.Dispatcher
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
import org.http4s.Response
import org.http4s.client.Client
import org.http4s.client.RequestKey

import javax.net.ssl.SSLContext
import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.control.NonFatal

class NettyClientBuilder[F[_]](
    idleTimeout: Duration,
    eventLoopThreads: Int,
    maxInitialLength: Int,
    maxHeaderSize: Int,
    maxChunkSize: Int,
    maxConnectionsPerKey: Int,
    transport: NettyTransport,
    sslContext: NettyClientBuilder.SSLContextOption,
    nettyChannelOptions: NettyChannelOptions,
    proxy: Option[Proxy]
)(implicit F: Async[F]) {
  private[this] val logger = org.log4s.getLogger

  type Self = NettyClientBuilder[F]

  private def copy(
      idleTimeout: Duration = idleTimeout,
      eventLoopThreads: Int = eventLoopThreads,
      maxInitialLength: Int = maxInitialLength,
      maxHeaderSize: Int = maxHeaderSize,
      maxChunkSize: Int = maxChunkSize,
      maxConnectionsPerKey: Int = maxConnectionsPerKey,
      transport: NettyTransport = transport,
      sslContext: NettyClientBuilder.SSLContextOption = sslContext,
      nettyChannelOptions: NettyChannelOptions = nettyChannelOptions,
      proxy: Option[Proxy] = proxy
  ): NettyClientBuilder[F] =
    new NettyClientBuilder[F](
      idleTimeout,
      eventLoopThreads,
      maxInitialLength,
      maxHeaderSize,
      maxChunkSize,
      maxConnectionsPerKey,
      transport,
      sslContext,
      nettyChannelOptions,
      proxy
    )

  def withNativeTransport: Self = copy(transport = NettyTransport.Native)
  def withNioTransport: Self = copy(transport = NettyTransport.Nio)
  def withMaxInitialLength(size: Int): Self = copy(maxInitialLength = size)
  def withMaxHeaderSize(size: Int): Self = copy(maxHeaderSize = size)
  def withMaxChunkSize(size: Int): Self = copy(maxChunkSize = size)
  def withMaxConnectionsPerKey(size: Int): Self = copy(maxConnectionsPerKey = size)
  def withIdleTimeout(duration: FiniteDuration): Self = copy(idleTimeout = duration)

  def withSSLContext(sslContext: SSLContext): Self =
    copy(sslContext = NettyClientBuilder.SSLContextOption.Provided(sslContext))

  def withoutSSL: Self =
    copy(sslContext = NettyClientBuilder.SSLContextOption.NoSSL)

  def withDefaultSSLContext: Self =
    copy(sslContext = NettyClientBuilder.SSLContextOption.TryDefaultSSLContext)

  def withNettyChannelOptions(opts: NettyChannelOptions): Self =
    copy(nettyChannelOptions = opts)

  /** Socket selector threads.
    * @param nThreads
    *   number of selector threads. Use <code>0</code> for netty default
    * @return
    *   an updated builder
    */
  def withEventLoopThreads(nThreads: Int): Self = copy(eventLoopThreads = nThreads)

  def withProxy(proxy: Proxy): Self = copy(proxy = Some(proxy))
  def withProxyFromSystemProperties: Self = copy(proxy = Proxy.fromSystemProperties)
  def withoutProxy: Self = copy(proxy = None)

  private def getEventLoop: EventLoopHolder[_ <: SocketChannel] =
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

  private def createBootstrap: Resource[F, Bootstrap] =
    Resource.make(F.delay {
      val bootstrap = new Bootstrap()
      getEventLoop.configure(bootstrap)
      nettyChannelOptions.foldLeft(bootstrap) { case (boot, (opt, value)) =>
        boot.option(opt, value)
      }
      bootstrap
    })(bs => F.delay(bs.config().group().shutdownGracefully()).liftToF)

  def resource: Resource[F, Client[F]] =
    Dispatcher[F].flatMap(disp =>
      createBootstrap.map { bs =>
        val config = Http4sChannelPoolMap.Config(
          maxInitialLength,
          maxHeaderSize,
          maxChunkSize,
          maxConnectionsPerKey,
          idleTimeout,
          proxy,
          sslContext
        )
        mkClient(new Http4sChannelPoolMap[F](bs, config), disp)
      })

  private def mkClient(pool: Http4sChannelPoolMap[F], dispatcher: Dispatcher[F]) =
    Client[F] { req =>
      val key = RequestKey.fromRequest(req)
      val pipelineKey = s"http4s-$key"
      val nettyConverter = new NettyModelConversion[F](dispatcher)

      for {
        channel <- pool.resource(key)
        responseResource <- Resource
          .eval(F.async_[Resource[F, Response[F]]] { cb =>
            val http4sHandler = new Http4sHandler[F](cb, dispatcher)
            channel.pipeline().addLast(pipelineKey, http4sHandler)
            logger.trace(s"Sending request to $key")
            channel.writeAndFlush(nettyConverter.toNettyRequest(req))
            logger.trace(s"After request to $key")
          })
        response <- responseResource
      } yield response
    }

  private case class EventLoopHolder[A <: SocketChannel](eventLoop: MultithreadEventLoopGroup)(
      implicit classTag: ClassTag[A]
  ) {
    def runtimeClass: Class[A] = classTag.runtimeClass.asInstanceOf[Class[A]]
    def configure(bootstrap: Bootstrap): Bootstrap =
      bootstrap
        .group(eventLoop)
        .channel(runtimeClass)
        .option(ChannelOption.TCP_NODELAY, java.lang.Boolean.TRUE)
        .option(ChannelOption.SO_KEEPALIVE, java.lang.Boolean.TRUE)
  }
}

object NettyClientBuilder {
  def apply[F[_]](implicit F: Async[F]): NettyClientBuilder[F] =
    new NettyClientBuilder[F](
      idleTimeout = 60.seconds,
      eventLoopThreads = 0,
      maxInitialLength = 4096,
      maxHeaderSize = 8192,
      maxChunkSize = 8192,
      maxConnectionsPerKey = 10,
      transport = NettyTransport.Native,
      sslContext = SSLContextOption.TryDefaultSSLContext,
      nettyChannelOptions = NettyChannelOptions.empty,
      proxy = Proxy.fromSystemProperties
    )

  private[client] sealed trait SSLContextOption extends Product with Serializable
  private[client] object SSLContextOption {
    case object NoSSL extends SSLContextOption
    case object TryDefaultSSLContext extends SSLContextOption
    final case class Provided(sslContext: SSLContext) extends SSLContextOption

    def toMaybeSSLContext(sco: SSLContextOption): Option[SSLContext] =
      sco match {
        case SSLContextOption.NoSSL => None
        case SSLContextOption.TryDefaultSSLContext => tryDefaultSslContext
        case SSLContextOption.Provided(context) => Some(context)
      }

    def tryDefaultSslContext: Option[SSLContext] =
      try Some(SSLContext.getDefault())
      catch {
        case NonFatal(_) => None
      }
  }
}
