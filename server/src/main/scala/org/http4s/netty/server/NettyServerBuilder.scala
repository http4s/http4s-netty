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

import cats.effect.Resource
import cats.effect.Sync
import cats.effect.kernel.Async
import cats.effect.std.Dispatcher
import cats.implicits._
import com.comcast.ip4s.IpAddress
import com.comcast.ip4s.SocketAddress
import fs2.io.net.tls.TLSParameters
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBufAllocator
import io.netty.channel._
import io.netty.channel.epoll.Epoll
import io.netty.channel.epoll.EpollEventLoopGroup
import io.netty.channel.epoll.EpollServerSocketChannel
import io.netty.channel.kqueue.KQueue
import io.netty.channel.kqueue.KQueueEventLoopGroup
import io.netty.channel.kqueue.KQueueServerSocketChannel
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.ssl.ApplicationProtocolConfig
import io.netty.handler.ssl.ApplicationProtocolConfig.Protocol
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior
import io.netty.handler.ssl.ApplicationProtocolNames
import io.netty.handler.ssl.ClientAuth
import io.netty.handler.ssl.IdentityCipherSuiteFilter
import io.netty.handler.ssl.JdkSslContext
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslHandler
import io.netty.incubator.channel.uring.IOUring
import io.netty.incubator.channel.uring.IOUringEventLoopGroup
import io.netty.incubator.channel.uring.IOUringServerSocketChannel
import org.http4s.HttpApp
import org.http4s.netty.NettyChannelOptions
import org.http4s.netty.NettyTransport
import org.http4s.server.Server
import org.http4s.server.ServiceErrorHandler
import org.http4s.server.defaults
import org.http4s.server.websocket.WebSocketBuilder

import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import scala.collection.immutable
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

final class NettyServerBuilder[F[_]] private (
    httpApp: WebSocketBuilder[F] => HttpApp[F],
    serviceErrorHandler: ServiceErrorHandler[F],
    socketAddress: SocketAddress[IpAddress],
    idleTimeout: Duration,
    eventLoopThreads: Int,
    maxInitialLineLength: Int,
    maxHeaderSize: Int,
    maxChunkSize: Int,
    transport: NettyTransport,
    banner: immutable.Seq[String],
    nettyChannelOptions: NettyChannelOptions,
    sslConfig: NettyServerBuilder.SslConfig,
    wsMaxFrameLength: Int
)(implicit F: Async[F]) {
  private val logger = org.log4s.getLogger
  type Self = NettyServerBuilder[F]

  private def copy(
      httpApp: WebSocketBuilder[F] => HttpApp[F] = httpApp,
      serviceErrorHandler: ServiceErrorHandler[F] = serviceErrorHandler,
      socketAddress: SocketAddress[IpAddress] = socketAddress,
      idleTimeout: Duration = idleTimeout,
      eventLoopThreads: Int = eventLoopThreads,
      maxInitialLineLength: Int = maxInitialLineLength,
      maxHeaderSize: Int = maxHeaderSize,
      maxChunkSize: Int = maxChunkSize,
      transport: NettyTransport = transport,
      banner: immutable.Seq[String] = banner,
      nettyChannelOptions: NettyChannelOptions = nettyChannelOptions,
      sslConfig: NettyServerBuilder.SslConfig = sslConfig,
      wsMaxFrameLength: Int = wsMaxFrameLength
  ): NettyServerBuilder[F] =
    new NettyServerBuilder[F](
      httpApp,
      serviceErrorHandler,
      socketAddress,
      idleTimeout,
      eventLoopThreads,
      maxInitialLineLength,
      maxHeaderSize,
      maxChunkSize,
      transport,
      banner,
      nettyChannelOptions,
      sslConfig,
      wsMaxFrameLength
    )

  private def getEventLoop: EventLoopHolder[_ <: ServerChannel] =
    transport match {
      case NettyTransport.Nio =>
        logger.info("Using NIO EventLoopGroup")
        EventLoopHolder[NioServerSocketChannel](
          new NioEventLoopGroup(1),
          new NioEventLoopGroup(eventLoopThreads)
        )
      case NettyTransport.Native =>
        if (IOUring.isAvailable) {
          logger.info("Using IOUring")
          EventLoopHolder[IOUringServerSocketChannel](
            new IOUringEventLoopGroup(1),
            new IOUringEventLoopGroup(eventLoopThreads))
        } else if (Epoll.isAvailable) {
          logger.info("Using Epoll")
          val acceptorEventLoopGroup = new EpollEventLoopGroup(1)
          acceptorEventLoopGroup.setIoRatio(100)
          val workerEventLoopGroup = new EpollEventLoopGroup(eventLoopThreads)
          workerEventLoopGroup.setIoRatio(80)
          EventLoopHolder[EpollServerSocketChannel](acceptorEventLoopGroup, workerEventLoopGroup)
        } else if (KQueue.isAvailable) {
          logger.info("Using KQueue")
          EventLoopHolder[KQueueServerSocketChannel](
            new KQueueEventLoopGroup(1),
            new KQueueEventLoopGroup(eventLoopThreads))
        } else {
          logger.info("Falling back to NIO EventLoopGroup")
          EventLoopHolder[NioServerSocketChannel](
            new NioEventLoopGroup(1),
            new NioEventLoopGroup(eventLoopThreads))
        }
    }

  def withHttpApp(httpApp: HttpApp[F]): Self = copy(httpApp = _ => httpApp)
  def withHttpWebSocketApp(httpApp: WebSocketBuilder[F] => HttpApp[F]): Self =
    copy(httpApp = httpApp)
  def bindSocketAddress(address: SocketAddress[IpAddress]): Self = copy(socketAddress = address)

  def bindHttp(port: Int = defaults.HttpPort, host: String = defaults.IPv4Host): Self =
    bindSocketAddress(SocketAddress.fromInetSocketAddress(new InetSocketAddress(host, port)))

  def bindLocal(port: Int): Self = bindHttp(port, defaults.IPv4Host)

  def bindAny(host: String = defaults.IPv4Host): Self = bindHttp(0, host)

  def withNativeTransport: Self = copy(transport = NettyTransport.Native)

  def withNioTransport: Self = copy(transport = NettyTransport.Nio)

  def withoutBanner: Self = copy(banner = Nil)

  def withMaxHeaderSize(size: Int): Self = copy(maxHeaderSize = size)

  def withMaxChunkSize(size: Int): Self = copy(maxChunkSize = size)

  def withMaxInitialLineLength(size: Int): Self = copy(maxInitialLineLength = size)

  def withServiceErrorHandler(handler: ServiceErrorHandler[F]): Self =
    copy(serviceErrorHandler = handler)

  def withNettyChannelOptions(opts: NettyChannelOptions): Self =
    copy(nettyChannelOptions = opts)

  /** Configures the server with TLS, using the provided `SSLContext` and `SSLParameters`. We only
    * look at the needClientAuth and wantClientAuth boolean params. For more control use overload.
    */
  @deprecated(message = "Use withSslContext without tlsParameters", since = "0.5.0-M2")
  def withSslContext(
      sslContext: SSLContext,
      tlsParameters: TLSParameters = TLSParameters.Default): Self = {
    val clientAuth =
      if (tlsParameters.needClientAuth) ClientAuth.REQUIRE
      else if (tlsParameters.wantClientAuth) ClientAuth.OPTIONAL
      else ClientAuth.NONE

    withSslContext(
      new JdkSslContext(
        sslContext,
        false,
        null,
        IdentityCipherSuiteFilter.INSTANCE,
        new ApplicationProtocolConfig(
          Protocol.ALPN,
          SelectorFailureBehavior.NO_ADVERTISE,
          SelectedListenerFailureBehavior.ACCEPT,
          ApplicationProtocolNames.HTTP_2,
          ApplicationProtocolNames.HTTP_1_1
        ),
        clientAuth,
        null,
        false
      ))
  }

  def withSslContext(sslContext: SslContext): Self =
    copy(sslConfig = new NettyServerBuilder.ContextWithParameters(sslContext))

  def withoutSsl: Self =
    copy(sslConfig = NettyServerBuilder.NoSsl)

  /** Socket selector threads.
    *
    * @param nThreads
    *   number of selector threads. Use <code>0</code> for netty default
    * @return
    *   an updated builder
    */
  def withEventLoopThreads(nThreads: Int): Self = copy(eventLoopThreads = nThreads)

  def withIdleTimeout(duration: FiniteDuration): Self = copy(idleTimeout = duration)

  private def bind(dispatcher: Dispatcher[F]) = {
    val resolvedAddress = {
      val unresolved = socketAddress.toInetSocketAddress
      if (unresolved.isUnresolved) new InetSocketAddress(unresolved.getHostName, unresolved.getPort)
      else unresolved
    }

    val config = NegotiationHandler.Config(
      maxInitialLineLength,
      maxHeaderSize,
      maxChunkSize,
      idleTimeout,
      wsMaxFrameLength)
    val loop = getEventLoop
    val server = new ServerBootstrap()
    server.option(ChannelOption.SO_BACKLOG, Int.box(1024))
    val channel = loop
      .configure(server)
      .childHandler(new ChannelInitializer[SocketChannel] {
        override def initChannel(ch: SocketChannel): Unit = void {
          val pipeline = ch.pipeline()
          sslConfig.toHandler(ch.alloc()) match {
            case Some(handler) =>
              logger.debug("Starting pipeline with TLS support and deferring protocol to ALPN")
              val negotiationHandler = new NegotiationHandler(
                config,
                httpApp,
                serviceErrorHandler,
                dispatcher
              )
              pipeline.addLast("ssl", handler)
              pipeline.addLast(negotiationHandler)

            case None =>
              logger.debug("Starting pipeline cleartext with HTTP/2 prior knowledge detection")
              val h2PriorKnowledgeDetection = new PriorKnowledgeDetectionHandler[F](
                config,
                httpApp,
                serviceErrorHandler,
                dispatcher
              )
              pipeline.addLast("h2-prior-knowledge-detection", h2PriorKnowledgeDetection)
          }
        }
      })
      .bind(resolvedAddress)
      .await()
      .channel()
    Bound(channel.localAddress().asInstanceOf[InetSocketAddress], loop, channel)
  }

  def resource: Resource[F, Server] =
    for {
      dispatcher <- Dispatcher.parallel[F]
      bound <- Resource.make(Sync[F].delay(bind(dispatcher))) {
        case Bound(address, loop, channel) =>
          Sync[F].delay {
            channel.close().awaitUninterruptibly()
            loop.shutdown()
            logger.info(s"All channels shut down. Server bound at ${address} shut down gracefully")
          }
      }
    } yield {
      val server = new Server {
        override def address = SocketAddress.fromInetSocketAddress(bound.address)

        override def isSecure: Boolean = sslConfig.isSecure
      }
      banner.foreach(logger.info(_))
      logger.info(s"Started Http4s Netty Server at ${server.baseUri}")
      server
    }

  def allocated: F[(Server, F[Unit])] = resource.allocated
  def stream: fs2.Stream[F, Server] = fs2.Stream.resource(resource)

  private case class EventLoopHolder[A <: ServerChannel](
      parent: MultithreadEventLoopGroup,
      eventLoop: MultithreadEventLoopGroup)(implicit classTag: ClassTag[A]) {
    def shutdown(): Unit = {
      eventLoop.shutdownGracefully(1000, 1500, TimeUnit.MILLISECONDS)
      parent.shutdownGracefully(1000, 1500, TimeUnit.MILLISECONDS)
      ()
    }

    def runtimeClass: Class[A] = classTag.runtimeClass.asInstanceOf[Class[A]]
    def configure(bootstrap: ServerBootstrap): ServerBootstrap = {
      val configured = bootstrap
        .group(parent, eventLoop)
        .channel(runtimeClass)
        .option(ChannelOption.SO_REUSEADDR, java.lang.Boolean.TRUE)
        .childOption(ChannelOption.SO_REUSEADDR, java.lang.Boolean.TRUE)
      nettyChannelOptions.foldLeft(configured) { case (c, (opt, optV)) => c.childOption(opt, optV) }
    }

  }
  private case class Bound(
      address: InetSocketAddress,
      holder: EventLoopHolder[_ <: ServerChannel],
      channel: Channel)
}

object NettyServerBuilder {
  private val DefaultWSMaxFrameLength = 65536

  def apply[F[_]](implicit F: Async[F]): NettyServerBuilder[F] =
    new NettyServerBuilder[F](
      httpApp = _ => HttpApp.notFound[F],
      serviceErrorHandler = org.http4s.server.DefaultServiceErrorHandler[F],
      socketAddress = org.http4s.server.defaults.IPv4SocketAddress,
      idleTimeout = org.http4s.server.defaults.IdleTimeout,
      eventLoopThreads = 0, // let netty decide
      maxInitialLineLength = 4096,
      maxHeaderSize = 8192,
      maxChunkSize = 8192,
      transport = NettyTransport.Native,
      banner = org.http4s.server.defaults.Banner,
      nettyChannelOptions = NettyChannelOptions.empty,
      sslConfig = NettyServerBuilder.NoSsl,
      wsMaxFrameLength = DefaultWSMaxFrameLength
    )

  private sealed trait SslConfig {
    def toHandler(alloc: ByteBufAllocator): Option[SslHandler]
    def isSecure: Boolean
  }

  private class ContextWithParameters(sslContext: SslContext) extends SslConfig {
    def toHandler(alloc: ByteBufAllocator): Option[SslHandler] = sslContext.newHandler(alloc).some
    def isSecure = true
  }

  private object NoSsl extends SslConfig {
    def toHandler(alloc: ByteBufAllocator): Option[SslHandler] = none[SslHandler]
    def isSecure = false
  }
}
