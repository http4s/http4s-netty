package org.http4s.client.netty

import cats.implicits._
import cats.effect.{Async, ConcurrentEffect, IO, Resource}
import com.typesafe.netty.http.HttpStreamsClientHandler
import io.netty.bootstrap.Bootstrap
import io.netty.channel.epoll.{Epoll, EpollEventLoopGroup, EpollSocketChannel}
import io.netty.channel.kqueue.{KQueue, KQueueEventLoopGroup, KQueueSocketChannel}
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.channel.{
  Channel,
  ChannelHandlerContext,
  ChannelInboundHandlerAdapter,
  ChannelInitializer,
  ChannelOption,
  MultithreadEventLoopGroup
}
import io.netty.handler.codec.http.{HttpRequestEncoder, HttpResponse, HttpResponseDecoder}
import io.netty.util.AttributeKey
import org.http4s.{Request, Response}
import org.http4s.Uri.Scheme
import org.http4s.client.{Client, RequestKey}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.reflect.ClassTag

class NettyClientBuilder[F[_]](
    idleTimeout: Duration,
    eventLoopThreads: Int,
    maxInitialLength: Int,
    maxHeaderSize: Int,
    maxChunkSize: Int,
    transport: NettyTransport,
    executionContext: ExecutionContext
)(implicit F: ConcurrentEffect[F]) {
  private[this] val logger = org.log4s.getLogger
  private[this] val attributeKey = AttributeKey.newInstance[RequestKey](classOf[RequestKey].getName)

  type Self = NettyClientBuilder[F]

  private def copy(
      idleTimeout: Duration = idleTimeout,
      eventLoopThreads: Int = eventLoopThreads,
      maxInitialLength: Int = maxInitialLength,
      maxHeaderSize: Int = maxHeaderSize,
      maxChunkSize: Int = maxChunkSize,
      transport: NettyTransport = transport,
      executionContext: ExecutionContext = executionContext
      //nettyChannelOptions: NettyClientBuilder.NettyChannelOptions = nettyChannelOptions,
      //sslConfig: NettyClientBuilder.SslConfig[F] = sslConfig
  ): NettyClientBuilder[F] =
    new NettyClientBuilder[F](
      idleTimeout,
      eventLoopThreads,
      maxInitialLength,
      maxHeaderSize,
      maxChunkSize,
      transport,
      executionContext
      //nettyChannelOptions,
      //sslConfig
    )

  def withNativeTransport: Self = copy(transport = NettyTransport.Native)
  def withNioTransport: Self = copy(transport = NettyTransport.Nio)
  def withMaxInitialLength(size: Int): Self = copy(maxInitialLength = size)
  def withMaxHeaderSize(size: Int): Self = copy(maxHeaderSize = size)
  def withMaxChunkSize(size: Int): Self = copy(maxChunkSize = size)
  def withIdleTimeout(duration: FiniteDuration): Self = copy(idleTimeout = duration)

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
    bootstrap.handler(new ChannelInitializer[SocketChannel] {
      override def initChannel(channel: SocketChannel): Unit = {
        println(s"Initializing $channel")

        /*val pipeline = channel.pipeline()
        pipeline.addLast( "response-decoder", new HttpResponseDecoder(maxInitialLength, maxHeaderSize, maxChunkSize))
        pipeline.addLast("request-encoder", new HttpRequestEncoder)
        pipeline.addLast("streaming-handler", new HttpStreamsClientHandler)
         */
        ()
      }
    })
  }

  def resource: Resource[F, Client[F]] =
    Resource.liftF(F.delay(setup)).map { bs =>
      Client[F] { req =>
        val key = RequestKey.fromRequest(req)
        val host = key.authority.host.value
        val port = (key.scheme, key.authority.port) match {
          case (_, Some(port)) => port
          case (Scheme.http, None) => 80
          case (Scheme.https, None) => 443
          case (_, None) => sys.error("No port")
        }
        for {
          /*channel <- Resource.make[F, Channel](F.delay {
            println(s"Connecting to $key, $port")
            val channelFuture = bs.connect(host, port)
            val channel = channelFuture.channel()
            channel.attr(attributeKey).set(key)
            channel.writeAndFlush(modelConversion.toNettyRequest(req))
            println("After writing request")
            channel
          })(channel => F.delay(channel.pipeline().remove(key.toString())).void)*/
          (channel, (res, cleanup)) <- Resource.liftF(
            F.delay {
                println(s"Connecting to $key, $port")
                val channelFuture = bs.connect(host, port)
                val channel = channelFuture.channel()
                channel
              }
              .flatMap(channel => responseCallback(channel, req, key)))
          there <- Resource.make(F.pure(res))(_ =>
            cleanup(channel) *> F.delay(channel.pipeline().remove(key.toString())).void)

        } yield there
      }

    }

  private def responseCallback(channel: Channel, request: Request[F], key: RequestKey) =
    Async.shift(executionContext) *> F.async[(Channel, (Response[F], Channel => F[Unit]))] { cb =>
      println("response channel" + channel)
      channel
        .pipeline()
        .addLast(
          "response-decoder",
          new HttpResponseDecoder(maxInitialLength, maxHeaderSize, maxChunkSize))
        .addLast("request-encoder", new HttpRequestEncoder)
        .addLast("streaming-handler", new HttpStreamsClientHandler)
        .addLast(
          key.toString(),
          new ChannelInboundHandlerAdapter {
            val modelConversion = new NettyModelConversion[F]()

            override def channelActive(ctx: ChannelHandlerContext): Unit = {
              println("channelActive")

              channel.attr(attributeKey).set(key)
              channel.writeAndFlush(modelConversion.toNettyRequest(request))
              println("After writing request")
              ctx.read()
              ()
            }

            override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
              println(s"ChannelRead; $ctx, $msg")

              msg match {
                case h: HttpResponse if ctx.channel().attr(attributeKey).get() == key =>
                  F.runAsync(modelConversion.fromNettyResponse(h)) { either =>
                      IO(cb(either.tupleLeft(channel)))
                    }
                    .unsafeRunSync()
                case _ => super.channelRead(ctx, msg)
              }
            }
          }
        )
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
      executionContext = ExecutionContext.global
    )
}

sealed trait NettyTransport extends Product with Serializable

object NettyTransport {
  case object Nio extends NettyTransport
  case object Native extends NettyTransport
}
