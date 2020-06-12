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
  MultithreadEventLoopGroup
}
import io.netty.handler.codec.http.{HttpRequestEncoder, HttpResponse, HttpResponseDecoder}
import io.netty.util.AttributeKey
import org.http4s.Response
import org.http4s.Uri.Scheme
import org.http4s.client.{Client, RequestKey}

import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag

class NettyClientBuilder[F[_]](
    eventLoopThreads: Int,
    maxInitialLength: Int,
    maxHeaderSize: Int,
    maxChunkSize: Int,
    transport: NettyTransport,
    executionContext: ExecutionContext
)(implicit F: ConcurrentEffect[F]) {
  private[this] val logger = org.log4s.getLogger
  private[this] val attributeKey = AttributeKey.newInstance[RequestKey](classOf[RequestKey].getName)

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
        val pipeline = channel.pipeline()
        pipeline.addLast(
          "response-decoder",
          new HttpResponseDecoder(maxInitialLength, maxHeaderSize, maxChunkSize))
        pipeline.addLast("request-encoder", new HttpRequestEncoder)
        pipeline.addLast("streaming-handler", new HttpStreamsClientHandler)
        //        pipeline.addLast("client-handler", new Http4ClientHandler[F](executionContext, callback))
        ()
      }
    })
  }

  def build(): Resource[F, Client[F]] =
    Resource.liftF(F.delay(setup)).map { bs =>
      val modelConversion = new NettyModelConversion[F]()
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
          channel <- Resource.make[F, Channel](F.delay {
            val channelFuture = bs.connect(host, port)
            val channel = channelFuture.channel()
            channel.attr(attributeKey).set(key)
            channel.writeAndFlush(modelConversion.toNettyRequest(req))
            channel
          })(channel => F.delay(channel.pipeline().remove(key.toString())).void)
          (res, cleanup) <- Resource.liftF(
            Async.shift(executionContext) *> F.async[(Response[F], Channel => F[Unit])] { cb =>
              channel
                .pipeline()
                .addLast(
                  key.toString(),
                  new ChannelInboundHandlerAdapter {
                    override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit =
                      msg match {
                        case h: HttpResponse if ctx.channel().attr(attributeKey).get() == key =>
                          F.runAsync(modelConversion.fromNettyResponse(h)) { either =>
                              IO(cb(either))
                            }
                            .unsafeRunSync()
                        case _ => ()
                      }
                  }
                )
              ()
            })
          there <- Resource.make(F.pure(res))(_ => cleanup(channel))

        } yield there
      }

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
  }

}

sealed trait NettyTransport extends Product with Serializable

object NettyTransport {
  case object Nio extends NettyTransport
  case object Native extends NettyTransport
}
