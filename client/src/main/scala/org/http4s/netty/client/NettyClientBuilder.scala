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
import cats.effect.Resource
import cats.effect.std.Dispatcher
import io.netty.bootstrap.Bootstrap
import org.http4s.Response
import org.http4s.client.Client
import org.http4s.client.RequestKey

import javax.net.ssl.SSLContext
import scala.concurrent.duration._

class NettyClientBuilder[F[_]](
    idleTimeout: Duration,
    eventLoopThreads: Int,
    maxInitialLength: Int,
    maxHeaderSize: Int,
    maxChunkSize: Int,
    maxConnectionsPerKey: Int,
    transport: NettyTransport,
    sslContext: SSLContextOption,
    nettyChannelOptions: NettyChannelOptions,
    proxy: Option[Proxy]
)(implicit F: Async[F]) {
  private[this] val logger = org.log4s.getLogger

  type Self = NettyClientBuilder[F]

  private def copy(
      idleTimeout: Duration = idleTimeout,
      eventLoopThreads: Int = eventLoopThreads,
      maxInitialLength: Int = maxInitialLength,
      maxHeaderSize: Int = maxHeaderSize,
      maxChunkSize: Int = maxChunkSize,
      maxConnectionsPerKey: Int = maxConnectionsPerKey,
      transport: NettyTransport = transport,
      sslContext: SSLContextOption = sslContext,
      nettyChannelOptions: NettyChannelOptions = nettyChannelOptions,
      proxy: Option[Proxy] = proxy
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
      proxy
    )

  def withNativeTransport: Self = copy(transport = NettyTransport.Native)
  def withNioTransport: Self = copy(transport = NettyTransport.Nio)
  def withMaxInitialLength(size: Int): Self = copy(maxInitialLength = size)
  def withMaxHeaderSize(size: Int): Self = copy(maxHeaderSize = size)
  def withMaxChunkSize(size: Int): Self = copy(maxChunkSize = size)
  def withMaxConnectionsPerKey(size: Int): Self = copy(maxConnectionsPerKey = size)
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

  def withProxy(proxy: Proxy): Self = copy(proxy = Some(proxy))
  def withProxyFromSystemProperties: Self = copy(proxy = Proxy.fromSystemProperties)
  def withoutProxy: Self = copy(proxy = None)

  private def createBootstrap: Resource[F, Bootstrap] =
    Resource.make(F.delay {
      val bootstrap = new Bootstrap()
      EventLoopHolder.fromTransport(transport, eventLoopThreads).configure(bootstrap)
      nettyChannelOptions.foldLeft(bootstrap) { case (boot, (opt, value)) =>
        boot.option(opt, value)
      }
      bootstrap
    })(bs => F.delay(bs.config().group().shutdownGracefully()).liftToF)

  def resource: Resource[F, Client[F]] =
    Dispatcher
      .parallel[F]
      .flatMap(disp =>
        createBootstrap.map { bs =>
          val config = Http4sChannelPoolMap.Config(
            maxInitialLength,
            maxHeaderSize,
            maxChunkSize,
            maxConnectionsPerKey,
            idleTimeout,
            proxy,
            sslContext
          )
          mkClient(new Http4sChannelPoolMap[F](bs, config), disp)
        })

  private def mkClient(pool: Http4sChannelPoolMap[F], dispatcher: Dispatcher[F]) =
    Client[F] { req =>
      val key = RequestKey.fromRequest(req)
      val pipelineKey = s"http4s-$key"
      val nettyConverter = new NettyModelConversion[F]

      for {
        channel <- pool.resource(key)
        nettyReq <- nettyConverter.toNettyRequest(req)
        responseResource <- Resource
          .eval(F.async_[Resource[F, Response[F]]] { cb =>
            val http4sHandler = new Http4sHandler[F](cb, dispatcher)
            channel.pipeline().addLast(pipelineKey, http4sHandler)
            logger.trace(s"Sending request to $key")
            dispatcher.unsafeRunAndForget(F.delay(channel.writeAndFlush(nettyReq)).liftToF)
            logger.trace(s"After request to $key")
          })
        response <- responseResource
      } yield response
    }
}

object NettyClientBuilder {
  def apply[F[_]](implicit F: Async[F]): NettyClientBuilder[F] =
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
      proxy = Proxy.fromSystemProperties
    )
}
