package org.http4s.netty.client

import cats.implicits._
import cats.effect.{Async, ConcurrentEffect, Resource}
import io.netty.bootstrap.Bootstrap
import io.netty.channel.epoll.{Epoll, EpollEventLoopGroup, EpollSocketChannel}
import io.netty.channel.kqueue.{KQueue, KQueueEventLoopGroup, KQueueSocketChannel}
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.channel.{ChannelOption, MultithreadEventLoopGroup}
import javax.net.ssl.SSLContext
import org.http4s.Response
import org.http4s.client.{Client, RequestKey}
import org.http4s.netty.{NettyChannelOptions, NettyModelConversion, NettyTransport}

import scala.concurrent.ExecutionContext
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
    executionContext: ExecutionContext
)(implicit F: ConcurrentEffect[F]) {
  private[this] val logger = org.log4s.getLogger
  private[this] val nettyConverter = new NettyModelConversion[F]

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
      executionContext: ExecutionContext = executionContext
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
      executionContext
    )

  def withNativeTransport: Self = copy(transport = NettyTransport.Native)
  def withNioTransport: Self = copy(transport = NettyTransport.Nio)
  def withExecutionContext(ec: ExecutionContext): Self = copy(executionContext = ec)
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
    * @param nThreads number of selector threads. Use <code>0</code> for netty default
    * @return an updated builder
    */
  def withEventLoopThreads(nThreads: Int): Self = copy(eventLoopThreads = nThreads)

  private def getEventLoop: EventLoopHolder[_ <: SocketChannel] =
    transport match {
      case NettyTransport.Nio =>
        EventLoopHolder[NioSocketChannel](new NioEventLoopGroup(eventLoopThreads))
      case NettyTransport.Native =>
        if (Epoll.isAvailable)
          EventLoopHolder[EpollSocketChannel](new EpollEventLoopGroup(eventLoopThreads))
        else if (KQueue.isAvailable)
          EventLoopHolder[KQueueSocketChannel](new KQueueEventLoopGroup(eventLoopThreads))
        else {
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
    })(bs => F.delay(bs.config().group().shutdownGracefully()).void)

  def resource: Resource[F, Client[F]] =
    createBootstrap.map { bs =>
      val config = Http4sChannelPoolMap.Config(
        maxInitialLength,
        maxHeaderSize,
        maxChunkSize,
        maxConnectionsPerKey,
        idleTimeout,
        sslContext)
      mkClient(new Http4sChannelPoolMap[F](bs, config))
    }

  private def mkClient(pool: Http4sChannelPoolMap[F]) =
    Client[F] { req =>
      val key = RequestKey.fromRequest(req)
      val pipelineKey = s"http4s-${key}"

      for {
        channel <- pool.resource(key)
        responseResource <- Resource
          .liftF(
            Async.shift(executionContext) *> F
              .async[Resource[F, Response[F]]] { cb =>
                val http4sHandler = new Http4sHandler[F](cb)
                channel.pipeline().addLast(pipelineKey, http4sHandler)
                logger.trace("Sending request")
                channel.writeAndFlush(nettyConverter.toNettyRequest(req))
                logger.trace("After request")
              })
        /*pool.withOnConnection { (c: Channel) =>
                val http4sHandler = new Http4sHandler[F](cb)
                c.pipeline().addLast(pipelineKey, http4sHandler)
                logger.trace("Sending request")
                c.writeAndFlush(nettyConverter.toNettyRequest(req))
                logger.trace("After request")
              }*/
        response <- responseResource
      } yield response
    }

  case class EventLoopHolder[A <: SocketChannel](eventLoop: MultithreadEventLoopGroup)(implicit
      classTag: ClassTag[A]
  ) {
    def runtimeClass: Class[A] = classTag.runtimeClass.asInstanceOf[Class[A]]
    def configure(bootstrap: Bootstrap) =
      bootstrap
        .group(eventLoop)
        .channel(runtimeClass)
        .option(ChannelOption.TCP_NODELAY, java.lang.Boolean.TRUE)
        .option(ChannelOption.SO_KEEPALIVE, java.lang.Boolean.TRUE)
  }
}

object NettyClientBuilder {
  def apply[F[_]](implicit F: ConcurrentEffect[F]): NettyClientBuilder[F] =
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
      executionContext = ExecutionContext.global
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
