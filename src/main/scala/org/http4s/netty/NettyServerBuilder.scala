package org.http4s.netty

import java.net.InetSocketAddress

import cats.implicits._
import cats.Applicative
import cats.effect.{ConcurrentEffect, Resource, Sync}
import com.typesafe.netty.http.HttpStreamsServerHandler
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
import javax.net.ssl.{SSLContext, SSLEngine, SSLParameters}
import org.http4s.HttpApp
import org.http4s.server.{defaults, Server, ServiceErrorHandler}

import scala.collection.immutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.reflect.ClassTag

final class NettyServerBuilder[F[_]](
    httpApp: HttpApp[F],
    serviceErrorHandler: ServiceErrorHandler[F],
    socketAddress: InetSocketAddress,
    idleTimeout: Duration,
    eventLoopThreads: Int, //let netty decide
    maxInitialLineLength: Int,
    maxHeaderSize: Int,
    maxChunkSize: Int,
    transport: NettyTransport,
    banner: immutable.Seq[String],
    executionContext: ExecutionContext,
    nettyChannelOptions: NettyServerBuilder.NettyChannelOptions,
    sslConfig: NettyServerBuilder.SslConfig[F]
)(implicit F: ConcurrentEffect[F]) {
  private val logger = org.log4s.getLogger

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
      nettyChannelOptions: NettyServerBuilder.NettyChannelOptions = nettyChannelOptions,
      sslConfig: NettyServerBuilder.SslConfig[F] = sslConfig
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
      sslConfig
    )

  private def getEventLoop: EventLoopHolder[_ <: ServerChannel] =
    transport match {
      case NettyTransport.Nio    =>
        EventLoopHolder[NioServerSocketChannel](new NioEventLoopGroup(eventLoopThreads))
      case NettyTransport.Native =>
        if (Epoll.isAvailable)
          EventLoopHolder[EpollServerSocketChannel](new EpollEventLoopGroup(eventLoopThreads))
        else if (KQueue.isAvailable)
          EventLoopHolder[KQueueServerSocketChannel](new KQueueEventLoopGroup(eventLoopThreads))
        else {
          logger.info("Falling back to NIO EventLoopGroup")
          EventLoopHolder[NioServerSocketChannel](new NioEventLoopGroup(eventLoopThreads))
        }
    }

  def withHttpApp(httpApp: HttpApp[F])              = copy(httpApp = httpApp)
  def withExecutionContext(ec: ExecutionContext)    = copy(executionContext = ec)
  def bindSocketAddress(address: InetSocketAddress) = copy(socketAddress = address)

  final def bindHttp(port: Int = defaults.HttpPort, host: String = defaults.Host) =
    bindSocketAddress(InetSocketAddress.createUnresolved(host, port))
  final def bindLocal(port: Int)                                                  = bindHttp(port, defaults.Host)
  final def bindAny(host: String = defaults.Host)                                 = bindHttp(0, host)

  def withNativeTransport                                                   = copy(transport = NettyTransport.Native)
  def withNioTransport                                                      = copy(transport = NettyTransport.Nio)
  def withoutBanner                                                         = copy(banner = Nil)
  def withMaxHeaderSize(size: Int)                                          = copy(maxHeaderSize = size)
  def withMaxChunkSize(size: Int)                                           = copy(maxChunkSize = size)
  def withMaxInitialLineLength(size: Int)                                   = copy(maxInitialLineLength = size)
  def withServiceErrorHandler(handler: ServiceErrorHandler[F])              = copy(serviceErrorHandler = handler)
  def withNettyChannelOptions(opts: NettyServerBuilder.NettyChannelOptions) = copy(nettyChannelOptions = opts)

  /** Configures the server with TLS, using the provided `SSLContext` and its
    * default `SSLParameters` */
  def withSslContext(sslContext: SSLContext) =
    copy(sslConfig = new NettyServerBuilder.ContextOnly[F](sslContext))

  /** Configures the server with TLS, using the provided `SSLContext` and `SSLParameters`. */
  def withSslContextAndParameters(sslContext: SSLContext, sslParameters: SSLParameters) =
    copy(sslConfig = new NettyServerBuilder.ContextWithParameters[F](sslContext, sslParameters))

  def withoutSsl =
    copy(sslConfig = new NettyServerBuilder.NoSsl[F]())

  /**
    * Socket selector threads.
    * @param nThreads number of selector threads. Use <code>0</code> for netty default
    * @return an updated builder
    */
  def withEventLoopThreads(nThreads: Int) = copy(eventLoopThreads = nThreads)

  def withIdleTimeout(duration: FiniteDuration) = copy(idleTimeout = duration)

  private def bind(tlsEngine: Option[SSLEngine]) = {
    val resolvedAddress =
      if (socketAddress.isUnresolved) new InetSocketAddress(socketAddress.getHostName, socketAddress.getPort) else socketAddress
    val loop            = getEventLoop
    val server          = new ServerBootstrap()
    val channel         = loop
      .configure(server)
      .childHandler(new ChannelInitializer[SocketChannel] {
        override def initChannel(ch: SocketChannel): Unit = {
          val pipeline = ch.pipeline()
          tlsEngine.foreach { engine =>
            pipeline.addLast("ssl", new SslHandler(engine))
          }
          pipeline
            .addLast(new HttpRequestDecoder(maxInitialLineLength, maxHeaderSize, maxChunkSize), new HttpResponseEncoder())
          if (idleTimeout.isFinite && idleTimeout.length > 0) {
            pipeline.addLast("idle-handler", new IdleStateHandler(0, 0, idleTimeout.length, idleTimeout.unit))
          }
          pipeline
            .addLast("serverStreamsHandler", new HttpStreamsServerHandler())
            .addLast("http4s", Http4sNettyHandler.default(app = httpApp, serviceErrorHandler, executionContext))
          ()
        }
      })
      .bind(resolvedAddress)
      .await()
      .channel()
    Bound(channel.localAddress().asInstanceOf[InetSocketAddress], loop, channel)
  }

  def resource: Resource[F, Server[F]] =
    for {
      maybeEngine <- Resource.liftF(createSSLEngine)
      bound       <- Resource.make(Sync[F].delay(bind(maybeEngine))) {
                 case Bound(address, loop, channel) =>
                   Sync[F].delay {
                     channel.close().awaitUninterruptibly()
                     loop.eventLoop.shutdownGracefully()
                     logger.info(s"All channels shut down. Server bound at ${address} shut down gracefully")
                   }
               }
    } yield {
      val server = new Server[F] {
        override def address: InetSocketAddress = bound.address

        override def isSecure: Boolean = sslConfig.isSecure
      }
      banner.foreach(logger.info(_))
      logger.info(s"Started Http4s Netty Server at ${server.baseUri}")
      server
    }

  private def createSSLEngine = {
    sslConfig.makeContext.flatMap(maybeCtx =>
      F.delay(maybeCtx.map { ctx =>
        val engine = ctx.createSSLEngine()
        engine.setUseClientMode(false)
        sslConfig.configureEngine(engine)
        engine
      })
    )
  }

  case class EventLoopHolder[A <: ServerChannel](eventLoop: MultithreadEventLoopGroup)(implicit
      classTag: ClassTag[A]
  ) {
    def shutdown(): Unit = {
      eventLoop.shutdownGracefully()
      ()
    }
    def runtimeClass: Class[A] = classTag.runtimeClass.asInstanceOf[Class[A]]
    def configure(bootstrap: ServerBootstrap) = {
      val configured = bootstrap
        .group(eventLoop)
        .channel(runtimeClass)
        .childOption(ChannelOption.AUTO_READ, java.lang.Boolean.FALSE)
      nettyChannelOptions.foldLeft(configured) { case (c, (opt, optV)) => c.childOption(opt, optV) }
    }

  }
  case class Bound(address: InetSocketAddress, holder: EventLoopHolder[_ <: ServerChannel], channel: Channel)
}

object NettyServerBuilder {
  def apply[F[_]](implicit F: ConcurrentEffect[F]): NettyServerBuilder[F] = {
    new NettyServerBuilder[F](
      httpApp = HttpApp.notFound[F],
      serviceErrorHandler = org.http4s.server.DefaultServiceErrorHandler[F],
      socketAddress = org.http4s.server.defaults.SocketAddress,
      idleTimeout = org.http4s.server.defaults.IdleTimeout,
      eventLoopThreads = 0, //let netty decide
      maxInitialLineLength = 4096,
      maxHeaderSize = 8192,
      maxChunkSize = 8192,
      transport = NettyTransport.Native,
      banner = org.http4s.server.defaults.Banner,
      executionContext = ExecutionContext.global,
      nettyChannelOptions = NettyChannelOptions.empty,
      sslConfig = new NettyServerBuilder.NoSsl[F]
    )
  }

  /** Ensure we construct our netty channel options in a typeful, immutable way, despite
    * the underlying being disgusting
    */
  sealed abstract class NettyChannelOptions {

    /** Prepend to the channel options **/
    def prepend[O](channelOption: ChannelOption[O], value: O): NettyChannelOptions

    /** Append to the channel options **/
    def append[O](channelOption: ChannelOption[O], value: O): NettyChannelOptions

    /** Remove a channel option, if present **/
    def remove[O](channelOption: ChannelOption[O]): NettyChannelOptions

    private[http4s] def foldLeft[O](initial: O)(f: (O, (ChannelOption[Any], Any)) => O): O
  }

  object NettyChannelOptions {
    val empty = new NettyCOptions(Vector.empty)
  }

  private[http4s] final class NettyCOptions(private[http4s] val underlying: Vector[(ChannelOption[Any], Any)])
      extends NettyChannelOptions {

    def prepend[O](channelOption: ChannelOption[O], value: O): NettyChannelOptions =
      new NettyCOptions((channelOption.asInstanceOf[ChannelOption[Any]], value: Any) +: underlying)

    def append[O](channelOption: ChannelOption[O], value: O): NettyChannelOptions =
      new NettyCOptions(underlying :+ ((channelOption.asInstanceOf[ChannelOption[Any]], value: Any)))

    def remove[O](channelOption: ChannelOption[O]): NettyChannelOptions =
      new NettyCOptions(underlying.filterNot(_._1 == channelOption))

    private[http4s] def foldLeft[O](initial: O)(f: (O, (ChannelOption[Any], Any)) => O) =
      underlying.foldLeft[O](initial)(f)
  }

  private sealed trait SslConfig[F[_]] {
    def makeContext: F[Option[SSLContext]]
    def configureEngine(sslEngine: SSLEngine): Unit
    def isSecure: Boolean
  }

  private class ContextOnly[F[_]](sslContext: SSLContext)(implicit F: Applicative[F]) extends SslConfig[F] {
    def makeContext = F.pure(sslContext.some)
    def configureEngine(engine: SSLEngine) = {
      val _ = engine
      ()
    }
    def isSecure    = true
  }

  private class ContextWithParameters[F[_]](sslContext: SSLContext, sslParameters: SSLParameters)(implicit F: Applicative[F])
      extends SslConfig[F] {
    def makeContext                        = F.pure(sslContext.some)
    def configureEngine(engine: SSLEngine) = engine.setSSLParameters(sslParameters)
    def isSecure                           = true
  }

  private class NoSsl[F[_]]()(implicit F: Applicative[F]) extends SslConfig[F] {
    def makeContext = F.pure(None)
    def configureEngine(engine: SSLEngine) = {
      val _ = engine
      ()
    }
    def isSecure    = false
  }
}

sealed trait NettyTransport extends Product with Serializable

object NettyTransport {
  case object Nio    extends NettyTransport
  case object Native extends NettyTransport
}
