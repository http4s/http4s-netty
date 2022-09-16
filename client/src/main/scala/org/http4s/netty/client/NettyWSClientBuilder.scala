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

import cats.effect.Async
import cats.effect.Deferred
import cats.effect.Resource
import cats.effect.std.Dispatcher
import cats.effect.std.Queue
import cats.syntax.all._
import fs2.io.net.tls.TLSParameters
import io.netty.bootstrap.Bootstrap
import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.ssl.SslHandler
import io.netty.handler.timeout.IdleStateHandler
import org.http4s.Uri
import org.http4s.Uri.Authority
import org.http4s.client.RequestKey
import org.http4s.client.websocket.WSClient
import org.http4s.client.websocket.WSConnection
import org.http4s.client.websocket.WSFrame
import org.http4s.client.websocket.WSRequest

import java.net.InetSocketAddress
import javax.net.ssl.SSLContext
import scala.concurrent.duration._

class NettyWSClientBuilder[F[_]](
    idleTimeout: Duration,
    eventLoopThreads: Int,
    transport: NettyTransport,
    sslContext: SSLContextOption,
    nettyChannelOptions: NettyChannelOptions
)(implicit F: Async[F]) {
  private[this] val logger = org.log4s.getLogger
  private val WS = Uri.Scheme.unsafeFromString("ws")
  private val WSS = Uri.Scheme.unsafeFromString("wss")

  type Self = NettyWSClientBuilder[F]

  private def copy(
      idleTimeout: Duration = idleTimeout,
      eventLoopThreads: Int = eventLoopThreads,
      transport: NettyTransport = transport,
      sslContext: SSLContextOption = sslContext,
      nettyChannelOptions: NettyChannelOptions = nettyChannelOptions
  ): NettyWSClientBuilder[F] =
    new NettyWSClientBuilder[F](
      idleTimeout,
      eventLoopThreads,
      transport,
      sslContext,
      nettyChannelOptions
    )

  def withNativeTransport: Self = copy(transport = NettyTransport.Native)
  def withNioTransport: Self = copy(transport = NettyTransport.Nio)
  def withIdleTimeout(duration: FiniteDuration): Self = copy(idleTimeout = duration)

  def withSSLContext(sslContext: SSLContext): Self =
    copy(sslContext = SSLContextOption.Provided(sslContext))

  def withoutSSL: Self =
    copy(sslContext = SSLContextOption.NoSSL)

  def withDefaultSSLContext: Self =
    copy(sslContext = SSLContextOption.TryDefaultSSLContext)

  def withNettyChannelOptions(opts: NettyChannelOptions): Self =
    copy(nettyChannelOptions = opts)

  /** Socket selector threads.
    * @param nThreads
    *   number of selector threads. Use <code>0</code> for netty default
    * @return
    *   an updated builder
    */
  def withEventLoopThreads(nThreads: Int): Self = copy(eventLoopThreads = nThreads)

  private def createBootstrap: Resource[F, Bootstrap] =
    Resource.make(F.delay {
      val bootstrap = new Bootstrap()
      EventLoopHolder.fromTransport(transport, eventLoopThreads).configure(bootstrap)
      nettyChannelOptions.foldLeft(bootstrap) { case (boot, (opt, value)) =>
        boot.option(opt, value)
      }
      bootstrap
    })(bs => F.delay(bs.config().group().shutdownGracefully()).liftToF)

  def resource: Resource[F, WSClient[F]] = for {
    bs <- createBootstrap
    disp <- Dispatcher[F]
  } yield mkWSClient(bs, disp)

  private def mkWSClient(bs: Bootstrap, dispatcher: Dispatcher[F]) =
    WSClient[F](respondToPings = false) { (req: WSRequest) =>
      val key = RequestKey(req.uri.scheme.getOrElse(WS), req.uri.authority.getOrElse(Authority()))
      logger.trace(s"connecting to $key")

      val socketAddress = key match {
        case RequestKey(_, Uri.Authority(_, host, Some(port))) =>
          new InetSocketAddress(host.value, port)
        case RequestKey(WS, Uri.Authority(_, host, None)) =>
          new InetSocketAddress(host.value, 80)
        case RequestKey(WSS, Uri.Authority(_, host, None)) =>
          new InetSocketAddress(host.value, 443)
        case _ =>
          throw new IllegalArgumentException(s"Unable to create socket address from $key")
      }

      val conn = for {
        queue <- Queue.unbounded[F, Either[Throwable, WSFrame]]
        closed <- Deferred[F, Unit]
        tupled <- Async[F].async_[Resource[F, WSConnection[F]]] { callback =>
          void {
            bs.handler(new ChannelInitializer[SocketChannel] {
              override def initChannel(ch: SocketChannel): Unit = void {
                logger.trace("initChannel")
                val pipeline = ch.pipeline
                (key.scheme, SSLContextOption.toMaybeSSLContext(sslContext)) match {
                  case (WSS, Some(context)) =>
                    void {
                      logger.trace("Creating SSL engine")

                      val engine =
                        context.createSSLEngine(socketAddress.getHostName, socketAddress.getPort)
                      val params = TLSParameters(endpointIdentificationAlgorithm = Some("HTTPS"))
                      engine.setUseClientMode(true)
                      engine.setSSLParameters(params.toSSLParameters)
                      pipeline.addLast("ssl", new SslHandler(engine))
                    }
                  case _ => ()
                }

                pipeline.addLast("http", new HttpClientCodec())
                pipeline.addLast("aggregate", new HttpObjectAggregator(8192))
                pipeline.addLast(
                  "websocket",
                  new Http4sWebsocketHandler[F](req, queue, closed, dispatcher, callback))
                if (idleTimeout.isFinite && idleTimeout.length > 0)
                  pipeline
                    .addLast(
                      "timeout",
                      new IdleStateHandler(0, 0, idleTimeout.length, idleTimeout.unit))
              }
            })
            dispatcher.unsafeRunSync(F.delay(bs.connect(socketAddress)).liftToF)
          }
        }
      } yield tupled

      Resource.eval(conn).flatMap(identity)
    }
}

object NettyWSClientBuilder {
  def apply[F[_]: Async]: NettyWSClientBuilder[F] =
    new NettyWSClientBuilder[F](
      idleTimeout = 60.seconds,
      eventLoopThreads = 0,
      transport = NettyTransport.Native,
      sslContext = SSLContextOption.TryDefaultSSLContext,
      nettyChannelOptions = NettyChannelOptions.empty
    )
}
