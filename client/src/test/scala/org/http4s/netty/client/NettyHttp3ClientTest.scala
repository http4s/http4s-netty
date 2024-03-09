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

package org.http4s.netty
package client

import cats.effect.IO
import cats.effect.kernel.Resource
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.netty.incubator.codec.http3.DefaultHttp3DataFrame
import io.netty.incubator.codec.http3.DefaultHttp3HeadersFrame
import io.netty.incubator.codec.http3.Http3
import io.netty.incubator.codec.http3.Http3DataFrame
import io.netty.incubator.codec.http3.Http3HeadersFrame
import io.netty.incubator.codec.http3.Http3RequestStreamInboundHandler
import io.netty.incubator.codec.http3.Http3ServerConnectionHandler
import io.netty.incubator.codec.quic.InsecureQuicTokenHandler
import io.netty.incubator.codec.quic.QuicChannel
import io.netty.incubator.codec.quic.QuicSslContextBuilder
import io.netty.incubator.codec.quic.QuicStreamChannel
import io.netty.util.ReferenceCountUtil
import munit.catseffect.IOFixture
import org.http4s.HttpVersion
import org.http4s.Request
import org.http4s.Uri
import org.http4s.client.Client

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory

class NettyHttp3ClientTest extends IOSuite {

  val client: IOFixture[Client[IO]] =
    resourceFixture(
      NettyHttp3ClientBuilder[IO].withTrustManager(InsecureTrustManagerFactory.INSTANCE).resource,
      "client")

  val server: IOFixture[Uri] =
    resourceFixture(
      PureNettyHttp3Server
        .resource(19999)
        .map(tuple => Uri.unsafeFromString(s"https://localhost:${tuple._2.getPort}")),
      "server")

  test("local") {
    val uri = server()
    client()
      .expect[String](Request[IO](uri = uri, httpVersion = HttpVersion.`HTTP/3`))
      .flatMap(s => IO.delay(assertEquals(s, "Hello World")))
  }
}

private[client] object PureNettyHttp3Server {
  def resource(port: Int): Resource[IO, (NioEventLoopGroup, InetSocketAddress)] =
    Resource.make(start(port))(group =>
      IO.blocking(group._1.shutdownGracefully(0L, 0L, TimeUnit.SECONDS)).void)

  def start(port: Int): IO[(NioEventLoopGroup, InetSocketAddress)] = IO.blocking {
    val group = new NioEventLoopGroup(1)
    val key = {
      val ks = KeyStore.getInstance("PKCS12")
      val psw = "password".toCharArray
      ks.load(getClass.getResourceAsStream("/teststore.p12"), psw)

      val kmf = KeyManagerFactory.getInstance("SunX509")
      kmf.init(ks, psw)
      kmf
    }
    val sslcontext = QuicSslContextBuilder
      .forServer(key, "password")
      .applicationProtocols(Http3.supportedApplicationProtocols() *)
      .build()
    val codec = Http3
      .newQuicServerCodecBuilder()
      .sslContext(sslcontext)
      .maxIdleTimeout(5000, TimeUnit.MILLISECONDS)
      .initialMaxData(10000000)
      .initialMaxStreamDataBidirectionalLocal(1000000)
      .initialMaxStreamDataBidirectionalRemote(1000000)
      .initialMaxStreamsBidirectional(100)
      .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
      .handler(new ChannelInitializer[QuicChannel] {
        override def initChannel(quic: QuicChannel): Unit = void {
          quic
            .pipeline()
            .addLast(new Http3ServerConnectionHandler(new ChannelInitializer[QuicStreamChannel] {
              val bytes = "Hello World".getBytes(StandardCharsets.UTF_8)

              override def initChannel(ch: QuicStreamChannel): Unit = void {
                ch.pipeline()
                  .addLast(new Http3RequestStreamInboundHandler {
                    var found = false
                    override def channelRead(ctx: ChannelHandlerContext, frame: Http3HeadersFrame)
                        : Unit = void {
                      val method = frame.headers().method()
                      val path = frame.headers().path()

                      if (method != null && method.toString == "GET") {
                        if (path.toString == "/") {
                          found = true
                          val headersFrame = new DefaultHttp3HeadersFrame();
                          headersFrame.headers().status("200");
                          headersFrame.headers().add("server", "netty");
                          headersFrame.headers().addInt("content-length", bytes.length);
                          ctx.write(headersFrame);
                          ctx
                            .writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(bytes)))
                          // .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT)
                          ()
                        }
                      } else {
                        found = false
                      }

                      ReferenceCountUtil.release(frame)
                    }

                    override def channelRead(ctx: ChannelHandlerContext, frame: Http3DataFrame)
                        : Unit = void {
                      ReferenceCountUtil.release(frame)
                    }

                    override def channelInputClosed(ctx: ChannelHandlerContext): Unit = void {
                      if (!found) {
                        val headersFrame = new DefaultHttp3HeadersFrame()
                        headersFrame.headers().status("404")
                        headersFrame.headers().add("server", "netty")
                        ctx
                          .writeAndFlush(headersFrame)
                          .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT)
                        ()
                      }
                    }
                  })
              }

            }))
        }
      })
      .build()

    val bs = new Bootstrap()
    val channel = bs
      .group(group)
      .channel(classOf[NioDatagramChannel])
      .handler(codec)
      .bind(new InetSocketAddress(port))
      .sync()
      .channel()

    group -> channel.localAddress().asInstanceOf[InetSocketAddress]
  }
}
