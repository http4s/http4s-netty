package example

import com.typesafe.netty.http.DefaultStreamedHttpResponse
import com.typesafe.netty.http.StreamedHttpRequest
import io.netty.handler.codec.http._
import com.typesafe.netty.http.HttpStreamsServerHandler
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel._
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel

object EchoServer {
  val group = new NioEventLoopGroup()

  def start(handler: ChannelHandler) = {
    val bootstrap = new ServerBootstrap()
    bootstrap
      .group(group)
      .channel(classOf[NioServerSocketChannel])
      .childOption(ChannelOption.AUTO_READ, java.lang.Boolean.TRUE)
      .childHandler(new ChannelInitializer[SocketChannel] {
        override def initChannel(ch: SocketChannel): Unit = {
          val pipeline = ch.pipeline()

          pipeline
            .addLast(new HttpRequestDecoder(), new HttpResponseEncoder())
            .addLast("serverStreamsHandler", new HttpStreamsServerHandler())
            .addLast(handler)
        }
      })
  }

  def handle(request: HttpRequest, response: HttpResponse): HttpResponse = {
    if (HttpUtil.isTransferEncodingChunked(request))
      HttpUtil.setTransferEncodingChunked(response, true)
    else if (HttpUtil.isContentLengthSet(request)) {
      val contentLength = HttpUtil.getContentLength(request)
      response.headers.set("Request-Content-Length", contentLength)
      HttpUtil.setContentLength(response, contentLength)
    } else HttpUtil.setContentLength(response, 0)
    response.headers.set("Request-Uri", request.uri)
    response
  }

  def echo(msg: Any) = {
    msg match {
      case req: FullHttpRequest =>
        val response = new DefaultFullHttpResponse(
          req.protocolVersion,
          HttpResponseStatus.OK,
          req.content
        )
        response.headers.set("Request-Type", "Full")
        handle(req, response)
      case req: StreamedHttpRequest =>
        val response = new DefaultStreamedHttpResponse(
          req.protocolVersion,
          HttpResponseStatus.OK,
          req
        )
        response.headers.set("Request-Type", "Streamed")
        handle(req, response)
      case _ =>
        throw new IllegalArgumentException("Unsupported message: " + msg)
    }
  }

  def main(args: Array[String]): Unit = {
    start(new AutoReadHandler {
      override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
        ctx.writeAndFlush(echo(msg))
      }
    }).bind(8080).await().get()
  }

  @Sharable
  class AutoReadHandler extends ChannelInboundHandlerAdapter {
    override def channelActive(ctx: ChannelHandlerContext): Unit = {
      ctx.read()
      ()
    }

    override def channelReadComplete(ctx: ChannelHandlerContext): Unit = {
      ctx.read()
      ()
    }
  }
}
