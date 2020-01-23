package example

import java.net.InetSocketAddress

import cats.effect.{Concurrent, ConcurrentEffect, Resource, Sync}
import com.typesafe.netty.http.HttpStreamsServerHandler
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.epoll.{Epoll, EpollEventLoopGroup, EpollServerSocketChannel}
import io.netty.channel.kqueue.{KQueue, KQueueEventLoopGroup, KQueueServerSocketChannel}
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.{Channel, ChannelInitializer, ChannelOption, MultithreadEventLoopGroup, ServerChannel}
import io.netty.handler.codec.http.{HttpRequestDecoder, HttpResponseEncoder}
import io.netty.handler.timeout.IdleStateHandler
import org.http4s.server.{Server, ServerBuilder, ServiceErrorHandler}
import org.http4s.{HttpApp, Request, Response}

import scala.collection.immutable
import scala.concurrent.duration.Duration
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
    banner: immutable.Seq[String]
    //sslBits: Option[NettySSLConfig],
    //ec: ExecutionContext,
    //enableWebsockets: Boolean = false,
    //maxWSPayloadLength: Option[Int],
    //nettyChannelOptions: NettyBuilder.NettyChannelOptions
)(implicit F: ConcurrentEffect[F])
    extends ServerBuilder[F] {
  private val logger = org.log4s.getLogger

  type Self = NettyBuilder[F]

  override protected implicit def F: Concurrent[F] = F

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
      banner: immutable.Seq[String] = banner
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
      banner
    )

  override def bindSocketAddress(socketAddress: InetSocketAddress): NettyBuilder[F] =
    copy(socketAddress = socketAddress)

  override def withServiceErrorHandler(
      serviceErrorHandler: Request[F] => PartialFunction[Throwable, F[Response[F]]]
  ): NettyBuilder[F] =
    copy(serviceErrorHandler = serviceErrorHandler)

  override def resource: Resource[F, Server[F]] = {
    for {
      bound <- Resource.make(Sync[F].delay(bind())) {
                case Bound(address, loop, channel) =>
                  Sync[F].delay {
                    channel.close().awaitUninterruptibly()
                    loop.shutdown()
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
  }

  private def getEventLoop: EventLoopHolder[_ <: ServerChannel] = transport match {
    case NettyTransport.Nio =>
      EventLoopHolder[NioServerSocketChannel](new NioEventLoopGroup(eventLoopThreads), new NioEventLoopGroup(2))
    case NettyTransport.Native =>
      if (Epoll.isAvailable)
        EventLoopHolder[EpollServerSocketChannel](new EpollEventLoopGroup(eventLoopThreads), new EpollEventLoopGroup(2))
      else if (KQueue.isAvailable)
        EventLoopHolder[KQueueServerSocketChannel](new KQueueEventLoopGroup(eventLoopThreads), new KQueueEventLoopGroup(2))
      else {
        logger.info("Falling back to NIO EventLoopGroup")
        EventLoopHolder[NioServerSocketChannel](new NioEventLoopGroup(eventLoopThreads), new NioEventLoopGroup(2))
      }
  }

  override def withBanner(banner: immutable.Seq[String]): NettyBuilder[F] = copy(banner = banner)

  def withHttpApp(httpApp: HttpApp[F]): NettyBuilder[F] = copy(httpApp = httpApp)

  def bind() = {
    val resolvedAddress = new InetSocketAddress(socketAddress.getHostName, socketAddress.getPort)
    val loop            = getEventLoop
    val server          = new ServerBootstrap()
    val channel = configure(loop, server)
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
            .addLast(new Http4sHandler[F](httpApp, serviceErrorHandler))
          ()
        }
      })
      .bind(resolvedAddress)
      .await()
      .channel()
    Bound(resolvedAddress, loop, channel)
  }

  def configure(holder: EventLoopHolder[_ <: ServerChannel], boostrap: ServerBootstrap) =
    boostrap
      .group(holder.parent, holder.child)
      .channel(holder.runtimeClass)
      .childOption(ChannelOption.AUTO_READ, java.lang.Boolean.FALSE)

  case class EventLoopHolder[A <: ServerChannel](parent: MultithreadEventLoopGroup, child: MultithreadEventLoopGroup)(
      implicit classTag: ClassTag[A]
  ) {
    def shutdown(): Unit = {
      child.shutdownGracefully()
      parent.shutdownGracefully()
      ()
    }
    def runtimeClass: Class[A] = classTag.runtimeClass.asInstanceOf[Class[A]]
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
      banner = org.http4s.server.defaults.Banner
    )
  }
}

sealed trait NettyTransport extends Product with Serializable

object NettyTransport {
  case object Nio    extends NettyTransport
  case object Native extends NettyTransport
}
