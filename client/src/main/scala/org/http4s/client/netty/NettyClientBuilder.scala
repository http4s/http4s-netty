package org.http4s.client.netty

import java.net.ConnectException

import cats.implicits._
import cats.effect.{Async, ConcurrentEffect, Resource}
import com.typesafe.netty.http.HttpStreamsClientHandler
import io.netty.bootstrap.Bootstrap
import io.netty.channel.epoll.{Epoll, EpollEventLoopGroup, EpollSocketChannel}
import io.netty.channel.kqueue.{KQueue, KQueueEventLoopGroup, KQueueSocketChannel}
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.channel.{Channel, ChannelInitializer, ChannelOption, MultithreadEventLoopGroup}
import io.netty.handler.codec.http.{HttpRequestEncoder, HttpResponseDecoder}
import io.netty.handler.ssl.SslHandler
import io.netty.handler.timeout.IdleStateHandler
import javax.net.ssl.SSLContext
import org.http4s.{Request, Response}
import org.http4s.Uri.Scheme
import org.http4s.client.netty.NettyClientBuilder.SSLContextOption
import org.http4s.client.{Client, RequestKey}
import org.http4s.netty.{NettyChannelOptions, NettyTransport}

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
    transport: NettyTransport,
    sslContext: SSLContextOption,
    nettyChannelOptions: NettyChannelOptions,
    executionContext: ExecutionContext
)(implicit F: ConcurrentEffect[F]) {
  private[this] val logger = org.log4s.getLogger

  type Self = NettyClientBuilder[F]

  private def copy(
      idleTimeout: Duration = idleTimeout,
      eventLoopThreads: Int = eventLoopThreads,
      maxInitialLength: Int = maxInitialLength,
      maxHeaderSize: Int = maxHeaderSize,
      maxChunkSize: Int = maxChunkSize,
      transport: NettyTransport = transport,
      sslContext: SSLContextOption = sslContext,
      nettyChannelOptions: NettyChannelOptions = nettyChannelOptions,
      executionContext: ExecutionContext = executionContext
  ): NettyClientBuilder[F] =
    new NettyClientBuilder[F](
      idleTimeout,
      eventLoopThreads,
      maxInitialLength,
      maxHeaderSize,
      maxChunkSize,
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
  def withIdleTimeout(duration: FiniteDuration): Self = copy(idleTimeout = duration)

  def withSSLContext(sslContext: SSLContext): Self =
    copy(sslContext = NettyClientBuilder.SSLContextOption.Provided(sslContext))

  def withoutSSL: Self =
    copy(sslContext = NettyClientBuilder.SSLContextOption.NoSSL)

  def withDefaultSSLContext: Self =
    copy(sslContext = NettyClientBuilder.SSLContextOption.TryDefaultSSLContext)

  def withNettyChannelOptions(opts: NettyChannelOptions): Self =
    copy(nettyChannelOptions = opts)

  /**
    * Socket selector threads.
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

  def setup: Bootstrap = {
    val bootstrap = new Bootstrap()
    getEventLoop.configure(bootstrap)
    nettyChannelOptions.foldLeft(bootstrap) { case (boot, (opt, value)) => boot.option(opt, value) }

    bootstrap.handler(new ChannelInitializer[SocketChannel] {
      override def initChannel(channel: SocketChannel): Unit = {
        logger.trace(s"Initializing $channel")

        val pipeline = channel.pipeline()
        if (idleTimeout.isFinite && idleTimeout.length > 0)
          pipeline
            .addFirst("timeout", new IdleStateHandler(0, 0, idleTimeout.length, idleTimeout.unit))
        pipeline.addFirst("streaming-handler", new HttpStreamsClientHandler)
        pipeline.addFirst("request-encoder", new HttpRequestEncoder)
        pipeline.addFirst(
          "response-decoder",
          new HttpResponseDecoder(maxInitialLength, maxHeaderSize, maxChunkSize))
        (
          Option(channel.attr(Http4sHandler.attributeKey).get()),
          SSLContextOption.toMaybeSSLContext(sslContext)) match {
          case (Some(RequestKey(Scheme.https, _)), Some(context)) =>
            logger.trace("Creating SSL engine")
            val engine = context.createSSLEngine()
            engine.setUseClientMode(true)
            pipeline.addFirst("ssl", new SslHandler(engine))
            ()
          case (_, _) => ()
        }
      }
    })
  }

  def resource: Resource[F, Client[F]] =
    Resource.liftF(F.delay(setup)).map { bs =>
      Client[F] { req =>
        for {
          (key, host, port) <- Resource.liftF(F.delay {
            val key = RequestKey.fromRequest(req)
            val host = key.authority.host.value
            val port = (key.scheme, key.authority.port) match {
              case (Scheme.http, None) => 80
              case (Scheme.https, None) => 443
              case (_, Some(port)) => port
              case (_, None) =>
                throw new ConnectException(
                  s"Not possible to find any port to connect to for key $key")
            }
            (key, host, port)
          })
          (channel, (res, cleanup)) <- Resource.liftF(
            F.delay {
                logger.trace(s"Connecting to $key, $port")
                val channelFuture = bs.connect(host, port)
                channelFuture.channel()
              }
              .flatMap(channel => responseCallback(channel, req, key)))
          there <- Resource.make(F.pure(res))(_ =>
            cleanup(channel) *> F.delay(channel.pipeline().remove(key.toString())).void)

        } yield there
      }

    }

  private def responseCallback(channel: Channel, request: Request[F], key: RequestKey) =
    Async.shift(executionContext) *> F.async[(Channel, (Response[F], Channel => F[Unit]))] { cb =>
      channel.attr(Http4sHandler.attributeKey).set(key)
      channel
        .pipeline()
        .addLast(key.toString(), new Http4sHandler[F](request, key, cb))
      ()
    }

  case class EventLoopHolder[A <: SocketChannel](eventLoop: MultithreadEventLoopGroup)(implicit
      classTag: ClassTag[A]
  ) {
    def shutdown(): Unit = {
      eventLoop.shutdownGracefully()
      ()
    }
    def runtimeClass: Class[A] = classTag.runtimeClass.asInstanceOf[Class[A]]
    def configure(bootstrap: Bootstrap) =
      bootstrap
        .group(eventLoop)
        .channel(runtimeClass)
        .option(ChannelOption.AUTO_READ, java.lang.Boolean.FALSE)
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
      transport = NettyTransport.Native,
      sslContext = SSLContextOption.TryDefaultSSLContext,
      nettyChannelOptions = NettyChannelOptions.empty,
      executionContext = ExecutionContext.global
    )

  private sealed trait SSLContextOption extends Product with Serializable
  private object SSLContextOption {
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
