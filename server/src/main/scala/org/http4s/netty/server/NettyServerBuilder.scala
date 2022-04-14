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

import java.net.InetSocketAddress
import cats.Applicative
import cats.effect.{ConcurrentEffect, Resource, Sync}
import cats.implicits._
import com.typesafe.netty.http.HttpStreamsServerHandler
import fs2.io.tls.TLSParameters
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel._
import io.netty.channel.epoll.{Epoll, EpollEventLoopGroup, EpollServerSocketChannel}
import io.netty.channel.kqueue.{KQueue, KQueueEventLoopGroup, KQueueServerSocketChannel}
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.{HttpRequestDecoder, HttpResponseEncoder}
import io.netty.handler.ssl.SslHandler
import io.netty.handler.timeout.IdleStateHandler
import io.netty.incubator.channel.uring.{IOUring, IOUringEventLoopGroup, IOUringServerSocketChannel}

import javax.net.ssl.{SSLContext, SSLEngine}
import org.http4s.HttpApp
import org.http4s.netty.{NettyChannelOptions, NettyTransport}
import org.http4s.server.{Server, ServiceErrorHandler, defaults}

import java.util.concurrent.TimeUnit
import scala.collection.immutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.reflect.ClassTag

final class NettyServerBuilder[F[_]] private (
    httpApp: HttpApp[F],
    serviceErrorHandler: ServiceErrorHandler[F],
    socketAddress: InetSocketAddress,
    idleTimeout: Duration,
    eventLoopThreads: Int,
    maxInitialLineLength: Int,
    maxHeaderSize: Int,
    maxChunkSize: Int,
    transport: NettyTransport,
    banner: immutable.Seq[String],
    executionContext: ExecutionContext,
    nettyChannelOptions: NettyChannelOptions,
    sslConfig: NettyServerBuilder.SslConfig[F],
    websocketsEnabled: Boolean,
    wsMaxFrameLength: Int
)(implicit F: ConcurrentEffect[F]) {
  private val logger = org.log4s.getLogger
  type Self = NettyServerBuilder[F]

  private def copy(
      httpApp: HttpApp[F] = httpApp,
      serviceErrorHandler: ServiceErrorHandler[F] = serviceErrorHandler,
      socketAddress: InetSocketAddress = socketAddress,
      idleTimeout: Duration = idleTimeout,
      eventLoopThreads: Int = eventLoopThreads,
      maxInitialLineLength: Int = maxInitialLineLength,
      maxHeaderSize: Int = maxHeaderSize,
      maxChunkSize: Int = maxChunkSize,
      transport: NettyTransport = transport,
      banner: immutable.Seq[String] = banner,
      executionContext: ExecutionContext = executionContext,
      nettyChannelOptions: NettyChannelOptions = nettyChannelOptions,
      sslConfig: NettyServerBuilder.SslConfig[F] = sslConfig,
      websocketsEnabled: Boolean = websocketsEnabled,
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
      executionContext,
      nettyChannelOptions,
      sslConfig,
      websocketsEnabled,
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
        } else
        if (Epoll.isAvailable) {
          logger.info("Using Epoll")
          val acceptorEventLoopGroup = new EpollEventLoopGroup(1)
          acceptorEventLoopGroup.setIoRatio(100)
          val workerEventLoopGroup = new EpollEventLoopGroup(eventLoopThreads)
          workerEventLoopGroup.setIoRatio(80)
          EventLoopHolder[EpollServerSocketChannel](
            acceptorEventLoopGroup,
            workerEventLoopGroup)
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

  def withHttpApp(httpApp: HttpApp[F]): Self = copy(httpApp = httpApp)
  def withExecutionContext(ec: ExecutionContext): Self = copy(executionContext = ec)
  def bindSocketAddress(address: InetSocketAddress): Self = copy(socketAddress = address)

  def bindHttp(port: Int = defaults.HttpPort, host: String = defaults.IPv4Host): Self =
    bindSocketAddress(InetSocketAddress.createUnresolved(host, port))
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
  def withWebsockets: Self = copy(websocketsEnabled = true)
  def withoutWebsockets: Self = copy(websocketsEnabled = false)

  /** Configures the server with TLS, using the provided `SSLContext` and `SSLParameters`. */
  def withSslContext(
      sslContext: SSLContext,
      tlsParameters: TLSParameters = TLSParameters.Default): Self =
    copy(sslConfig = new NettyServerBuilder.ContextWithParameters[F](sslContext, tlsParameters))

  def withoutSsl: Self =
    copy(sslConfig = new NettyServerBuilder.NoSsl[F]())

  /** Socket selector threads.
    * @param nThreads
    *   number of selector threads. Use <code>0</code> for netty default
    * @return
    *   an updated builder
    */
  def withEventLoopThreads(nThreads: Int): Self = copy(eventLoopThreads = nThreads)

  def withIdleTimeout(duration: FiniteDuration): Self = copy(idleTimeout = duration)

  private def bind(tlsEngine: Option[SSLEngine]) = {
    val resolvedAddress =
      if (socketAddress.isUnresolved)
        new InetSocketAddress(socketAddress.getHostName, socketAddress.getPort)
      else socketAddress
    val loop = getEventLoop
    val server = new ServerBootstrap()
    val channel = loop
      .configure(server)
      .childHandler(new ChannelInitializer[SocketChannel] {
        override def initChannel(ch: SocketChannel): Unit = {
          val pipeline = ch.pipeline()
          tlsEngine.foreach { engine =>
            pipeline.addLast("ssl", new SslHandler(engine))
          }
          pipeline.addLast(
            "http-decoder",
            new HttpRequestDecoder(maxInitialLineLength, maxHeaderSize, maxChunkSize))
          pipeline.addLast("http-encoder", new HttpResponseEncoder())
          if (idleTimeout.isFinite && idleTimeout.length > 0)
            pipeline.addLast(
              "idle-handler",
              new IdleStateHandler(0, 0, idleTimeout.length, idleTimeout.unit))
          pipeline
            .addLast("serverStreamsHandler", new HttpStreamsServerHandler())
            .addLast(
              "http4s",
              if (websocketsEnabled)
                Http4sNettyHandler
                  .websocket(httpApp, serviceErrorHandler, wsMaxFrameLength, executionContext)
              else Http4sNettyHandler.default(app = httpApp, serviceErrorHandler, executionContext)
            )
          ()
        }
      })
      .bind(resolvedAddress)
      .await()
      .channel()
    Bound(channel.localAddress().asInstanceOf[InetSocketAddress], loop, channel)
  }

  def resource: Resource[F, Server] =
    for {
      maybeEngine <- Resource.eval(createSSLEngine)
      bound <- Resource.make(Sync[F].delay(bind(maybeEngine))) {
        case Bound(address, loop, channel) =>
          Sync[F].delay {
            channel.close().awaitUninterruptibly()
            loop.shutdown()
            logger.info(s"All channels shut down. Server bound at ${address} shut down gracefully")
          }
      }
    } yield {
      val server = new Server {
        override def address: InetSocketAddress = bound.address

        override def isSecure: Boolean = sslConfig.isSecure
      }
      banner.foreach(logger.info(_))
      logger.info(s"Started Http4s Netty Server at ${server.baseUri}")
      server
    }

  def allocated: F[(Server, F[Unit])] = resource.allocated
  def stream = fs2.Stream.resource(resource)

  private def createSSLEngine =
    sslConfig.makeContext.flatMap(maybeCtx =>
      F.delay(maybeCtx.map { ctx =>
        val engine = ctx.createSSLEngine()
        engine.setUseClientMode(false)
        sslConfig.configureEngine(engine)
        engine
      }))

  case class EventLoopHolder[A <: ServerChannel](
      parent: MultithreadEventLoopGroup,
      eventLoop: MultithreadEventLoopGroup)(implicit classTag: ClassTag[A]) {
    def shutdown(): Unit = {
      eventLoop.shutdownGracefully(1000, 1500, TimeUnit.MILLISECONDS)
      parent.shutdownGracefully(1000, 1500, TimeUnit.MILLISECONDS)
      ()
    }
    def runtimeClass: Class[A] = classTag.runtimeClass.asInstanceOf[Class[A]]
    def configure(bootstrap: ServerBootstrap) = {
      val configured = bootstrap
        .group(parent, eventLoop)
        .channel(runtimeClass)
        .option(ChannelOption.SO_REUSEADDR, java.lang.Boolean.TRUE)
        .childOption(ChannelOption.SO_REUSEADDR, java.lang.Boolean.TRUE)
        .childOption(ChannelOption.AUTO_READ, java.lang.Boolean.FALSE)
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

  def apply[F[_]](implicit F: ConcurrentEffect[F]): NettyServerBuilder[F] =
    new NettyServerBuilder[F](
      httpApp = HttpApp.notFound[F],
      serviceErrorHandler = org.http4s.server.DefaultServiceErrorHandler[F],
      socketAddress = org.http4s.server.defaults.IPv4SocketAddress,
      idleTimeout = org.http4s.server.defaults.IdleTimeout,
      eventLoopThreads = 0, // let netty decide
      maxInitialLineLength = 4096,
      maxHeaderSize = 8192,
      maxChunkSize = 8192,
      transport = NettyTransport.Native,
      banner = org.http4s.server.defaults.Banner,
      executionContext = ExecutionContext.global,
      nettyChannelOptions = NettyChannelOptions.empty,
      sslConfig = new NettyServerBuilder.NoSsl[F],
      websocketsEnabled = false,
      wsMaxFrameLength = DefaultWSMaxFrameLength
    )

  private sealed trait SslConfig[F[_]] {
    def makeContext: F[Option[SSLContext]]
    def configureEngine(sslEngine: SSLEngine): Unit
    def isSecure: Boolean
  }

  private class ContextWithParameters[F[_]](sslContext: SSLContext, tlsParameters: TLSParameters)(
      implicit F: Applicative[F])
      extends SslConfig[F] {
    def makeContext = F.pure(sslContext.some)
    def configureEngine(engine: SSLEngine) = engine.setSSLParameters(tlsParameters.toSSLParameters)
    def isSecure = true
  }

  private class NoSsl[F[_]]()(implicit F: Applicative[F]) extends SslConfig[F] {
    def makeContext = F.pure(None)
    def configureEngine(engine: SSLEngine) = {
      val _ = engine
      ()
    }
    def isSecure = false
  }
}
