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

case class NettyBuilder[F[_]](
    app: HttpApp[F],
    serviceErrorHandler: ServiceErrorHandler[F],
    socketAddress: InetSocketAddress = org.http4s.server.defaults.SocketAddress,
    idleTimeout: Duration = org.http4s.server.defaults.IdleTimeout,
    eventLoopThreads: Int = 0, //let netty decide
    maxInitialLineLength: Int = 4096,
    maxHeaderSize: Int = 8192,
    maxChunkSize: Int = 8192,
    //sslBits: Option[NettySSLConfig],
    transport: NettyTransport = NettyTransport.Native,
    //ec: ExecutionContext,
    //enableWebsockets: Boolean = false,
    //maxWSPayloadLength: Option[Int],
    banner: immutable.Seq[String] = org.http4s.server.defaults.Banner
    //nettyChannelOptions: NettyBuilder.NettyChannelOptions
)(implicit F: ConcurrentEffect[F])
    extends ServerBuilder[F] {
  private val logger = org.log4s.getLogger

  type Self = NettyBuilder[F]

  override protected implicit def F: Concurrent[F] = F

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
                    loop.shutdownGracefully()
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

  private def getEventLoop: EventLoopHolder[_] = transport match {
    case NettyTransport.Nio =>
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

  override def withBanner(banner: immutable.Seq[String]): NettyBuilder[F] = copy(banner = banner)

  def bind() = {
    val resolvedAddress = new InetSocketAddress(socketAddress.getHostName, socketAddress.getPort)
    val holder          = getEventLoop
    val server          = new ServerBootstrap()
    val channel = holder
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
            .addLast(new Http4sHandler[F](app, serviceErrorHandler))
          ()
        }
      })
      .bind(resolvedAddress)
      .await()
      .channel()
    Bound(resolvedAddress, holder.eventloop, channel)
  }

  case class EventLoopHolder[A <: ServerChannel](eventloop: MultithreadEventLoopGroup)(implicit classTag: ClassTag[A]) {
    def configure(boostrap: ServerBootstrap) =
      boostrap
        .group(eventloop)
        .channel(classTag.runtimeClass.asInstanceOf[Class[A]])
        .childOption(ChannelOption.AUTO_READ, java.lang.Boolean.FALSE)
  }

  case class Bound(address: InetSocketAddress, eventLoop: MultithreadEventLoopGroup, channel: Channel)
}

object NettyBuilder {}

sealed trait NettyTransport extends Product with Serializable

object NettyTransport {
  case object Nio    extends NettyTransport
  case object Native extends NettyTransport
}
