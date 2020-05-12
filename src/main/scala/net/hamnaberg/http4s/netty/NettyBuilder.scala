package net.hamnaberg.http4s.netty

import java.net.InetSocketAddress

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
import io.netty.handler.timeout.IdleStateHandler
import org.http4s.HttpApp
import org.http4s.server.{Server, ServiceErrorHandler}

import scala.collection.immutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.reflect.ClassTag

class NettyBuilder[F[_]](
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
    executionContext: ExecutionContext
    //sslBits: Option[NettySSLConfig],
    //enableWebsockets: Boolean = false,
    //maxWSPayloadLength: Option[Int],
    //nettyChannelOptions: NettyBuilder.NettyChannelOptions
)(implicit F: ConcurrentEffect[F]) {
  private val logger = org.log4s.getLogger

  type Self = NettyBuilder[F]

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
      executionContext: ExecutionContext = executionContext
  ): NettyBuilder[F] =
    new NettyBuilder[F](
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
      executionContext
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

  def withHttpApp(httpApp: HttpApp[F]): NettyBuilder[F]          = copy(httpApp = httpApp)
  def withExcutionContext(ec: ExecutionContext): NettyBuilder[F] = copy(executionContext = ec)
  def withPort(port: Int)                                        = copy(socketAddress = InetSocketAddress.createUnresolved(socketAddress.getHostName, port))
  def withHost(host: String)                                     = copy(socketAddress = InetSocketAddress.createUnresolved(host, socketAddress.getPort))
  def withHostAndPort(host: String, port: Int)                   =
    copy(socketAddress = InetSocketAddress.createUnresolved(host, port))
  def withNativeTransport                                        = copy(transport = NettyTransport.Native)
  def withNioTransport                                           = copy(transport = NettyTransport.Nio)
  def withoutBanner                                              = copy(banner = Nil)
  def withMaxHeaderSize(size: Int)                               = copy(maxHeaderSize = size)
  def withMaxChunkSize(size: Int)                                = copy(maxChunkSize = size)
  def withMaxInitialLineLength(size: Int)                        = copy(maxInitialLineLength = size)
  def withServiceErrorHandler(handler: ServiceErrorHandler[F])   = copy(serviceErrorHandler = handler)

  /**
    * Socket selector threads.
    * @param nThreads number of selector threads. Use <code>0</code> for netty default
    * @return an updated builder
    */
  def withEventLoopThreads(nThreads: Int) = copy(eventLoopThreads = nThreads)

  def withIdleTimeout(duration: FiniteDuration) = copy(idleTimeout = duration)

  private def bind() = {
    val resolvedAddress = new InetSocketAddress(socketAddress.getHostName, socketAddress.getPort)
    val loop            = getEventLoop
    val server          = new ServerBootstrap()
    val channel         = loop
      .configure(server)
      .childHandler(new ChannelInitializer[SocketChannel] {
        override def initChannel(ch: SocketChannel): Unit = {
          val pipeline = ch.pipeline()
          pipeline
            .addLast(new HttpRequestDecoder(maxInitialLineLength, maxHeaderSize, maxChunkSize), new HttpResponseEncoder())
          if (idleTimeout.isFinite() && idleTimeout.length > 0) {
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
    Bound(resolvedAddress, loop, channel)
  }

  def resource: Resource[F, Server[F]] =
    for {
      bound <- Resource.make(Sync[F].delay(bind())) {
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

        override def isSecure: Boolean = false
      }
      banner.foreach(logger.info(_))
      logger.info(s"Started Http4s Netty Server at ${server.baseUri}")
      server
    }

  case class EventLoopHolder[A <: ServerChannel](eventLoop: MultithreadEventLoopGroup)(implicit
      classTag: ClassTag[A]
  ) {
    def shutdown(): Unit = {
      eventLoop.shutdownGracefully()
      ()
    }
    def runtimeClass: Class[A]                = classTag.runtimeClass.asInstanceOf[Class[A]]
    def configure(bootstrap: ServerBootstrap) =
      bootstrap
        .group(eventLoop)
        .channel(runtimeClass)
        .childOption(ChannelOption.AUTO_READ, java.lang.Boolean.FALSE)

  }
  case class Bound(address: InetSocketAddress, holder: EventLoopHolder[_ <: ServerChannel], channel: Channel)
}

object NettyBuilder {
  def apply[F[_]](implicit F: ConcurrentEffect[F]): NettyBuilder[F] = {
    new NettyBuilder[F](
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
      executionContext = ExecutionContext.global
    )
  }
}

sealed trait NettyTransport extends Product with Serializable

object NettyTransport {
  case object Nio    extends NettyTransport
  case object Native extends NettyTransport
}
