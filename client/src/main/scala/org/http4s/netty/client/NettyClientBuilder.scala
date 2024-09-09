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
import io.netty.bootstrap.Bootstrap
import io.netty.channel.ChannelOption
import org.http4s.Headers
import org.http4s.client.Client
import org.http4s.headers.`User-Agent`

import javax.net.ssl.SSLContext
import scala.concurrent.duration.*

class NettyClientBuilder[F[_]](
    idleTimeout: Duration,
    readTimeout: Duration,
    eventLoopThreads: Int,
    maxInitialLength: Int,
    maxHeaderSize: Int,
    maxChunkSize: Int,
    maxConnectionsPerKey: Int,
    transport: NettyTransport,
    sslContext: SSLContextOption,
    nettyChannelOptions: NettyChannelOptions,
    proxy: Option[Proxy],
    http2: Boolean,
    defaultRequestHeaders: Headers
)(implicit F: Async[F]) {
  type Self = NettyClientBuilder[F]

  private def copy(
      idleTimeout: Duration = idleTimeout,
      readTimeout: Duration = readTimeout,
      eventLoopThreads: Int = eventLoopThreads,
      maxInitialLength: Int = maxInitialLength,
      maxHeaderSize: Int = maxHeaderSize,
      maxChunkSize: Int = maxChunkSize,
      maxConnectionsPerKey: Int = maxConnectionsPerKey,
      transport: NettyTransport = transport,
      sslContext: SSLContextOption = sslContext,
      nettyChannelOptions: NettyChannelOptions = nettyChannelOptions,
      proxy: Option[Proxy] = proxy,
      http2: Boolean = http2,
      defaultRequestHeaders: Headers = defaultRequestHeaders
  ): NettyClientBuilder[F] =
    new NettyClientBuilder[F](
      idleTimeout,
      readTimeout,
      eventLoopThreads,
      maxInitialLength,
      maxHeaderSize,
      maxChunkSize,
      maxConnectionsPerKey,
      transport,
      sslContext,
      nettyChannelOptions,
      proxy,
      http2,
      defaultRequestHeaders
    )

  def withNativeTransport: Self = copy(transport = NettyTransport.defaultFor(Os.get))
  def withNioTransport: Self = copy(transport = NettyTransport.Nio)
  def withTransport(transport: NettyTransport): Self = copy(transport = transport)
  def withMaxInitialLength(size: Int): Self = copy(maxInitialLength = size)
  def withMaxHeaderSize(size: Int): Self = copy(maxHeaderSize = size)
  def withMaxChunkSize(size: Int): Self = copy(maxChunkSize = size)
  def withMaxConnectionsPerKey(size: Int): Self = copy(maxConnectionsPerKey = size)

  def withIdleTimeout(duration: Duration): Self = copy(idleTimeout = duration)
  def withReadTimeout(duration: Duration): Self = copy(readTimeout = duration)

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
  def withHttp2: Self = copy(http2 = true)
  def withoutHttp2: Self = copy(http2 = false)

  def withUserAgent(useragent: `User-Agent`): NettyClientBuilder[F] =
    copy(defaultRequestHeaders = defaultRequestHeaders.put(useragent))

  def withDefaultRequestHeaders(headers: Headers): NettyClientBuilder[F] =
    copy(defaultRequestHeaders = headers)

  private def createBootstrap: Resource[F, Bootstrap] =
    Resource.make(F.delay {
      val bootstrap = new Bootstrap()
      EventLoopHolder.fromTransport(transport, eventLoopThreads).configure(bootstrap)
      bootstrap.option(ChannelOption.TCP_NODELAY, java.lang.Boolean.TRUE)
      nettyChannelOptions.foldLeft(bootstrap) { case (boot, (opt, value)) =>
        boot.option(opt, value)
      }
      bootstrap
    })(bs => F.delay(bs.config().group().shutdownGracefully()).liftToF)

  def resource: Resource[F, Client[F]] =
    createBootstrap.map { bs =>
      val config = Http4sChannelPoolMap.Config(
        maxInitialLength,
        maxHeaderSize,
        maxChunkSize,
        maxConnectionsPerKey,
        idleTimeout,
        proxy,
        sslContext,
        http2,
        defaultRequestHeaders,
        readTimeout
      )
      Client[F](new Http4sChannelPoolMap[F](bs, config).run)
    }
}

object NettyClientBuilder {
  def apply[F[_]](implicit F: Async[F]): NettyClientBuilder[F] =
    new NettyClientBuilder[F](
      idleTimeout = 60.seconds,
      readTimeout = 60.seconds,
      eventLoopThreads = 0,
      maxInitialLength = 4096,
      maxHeaderSize = 8192,
      maxChunkSize = 8192,
      maxConnectionsPerKey = 10,
      transport = NettyTransport.defaultFor(Os.get),
      sslContext = SSLContextOption.TryDefaultSSLContext,
      nettyChannelOptions = NettyChannelOptions.empty,
      proxy = Proxy.fromSystemProperties,
      http2 = false,
      defaultRequestHeaders = Headers()
    )
}
