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
import cats.implicits._
import com.typesafe.netty.http.HttpStreamsClientHandler
import fs2.io.net.tls.TLSParameters
import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.pool.AbstractChannelPoolHandler
import io.netty.channel.pool.AbstractChannelPoolMap
import io.netty.channel.pool.FixedChannelPool
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.ssl.SslHandler
import io.netty.handler.timeout.IdleStateHandler
import io.netty.util.concurrent.Future
import org.http4s.Request
import org.http4s.Response
import org.http4s.Uri
import org.http4s.Uri.Scheme
import org.http4s.client.RequestKey

import java.net.ConnectException
import java.nio.channels.ClosedChannelException
import scala.concurrent.duration.Duration

private[client] class Http4sChannelPoolMap[F[_]](
    bootstrap: Bootstrap,
    config: Http4sChannelPoolMap.Config,
    dispatcher: Dispatcher[F]
)(implicit F: Async[F])
    extends AbstractChannelPoolMap[RequestKey, FixedChannelPool] {

  private[this] val logger = org.log4s.getLogger
  private val modelConversion = new NettyModelConversion[F]

  def run(request: Request[F]): Resource[F, Response[F]] = {
    val key = RequestKey.fromRequest(request)
    val pool = get(key).asInstanceOf[MyFixedChannelPool]
    val handler = pool.handler()

    Resource
      .make(Http4sChannelPoolMap.fromFuture(pool.acquire())) { channel =>
        Http4sChannelPoolMap.fromFuture(pool.release(channel)).void
      }
      .flatMap(channel => handler.dispatch(channel, request))
  }

  override def newPool(key: RequestKey): FixedChannelPool =
    new MyFixedChannelPool(bootstrap, config.maxConnections, key)

  class MyFixedChannelPool(bs: Bootstrap, maxConnections: Int, key: RequestKey)
      extends FixedChannelPool(bs, new PoolHandler(key, config), maxConnections) {

    override def handler(): PoolHandler =
      super.handler().asInstanceOf[PoolHandler]

    override def connectChannel(bs: Bootstrap): ChannelFuture = {
      val host = key.authority.host.value
      val port = (key.scheme, key.authority.port) match {
        case (Scheme.http, None) => 80
        case (Scheme.https, None) => 443
        case (_, Some(port)) => port
        case (_, None) =>
          throw new ConnectException(s"Not possible to find any port to connect to for key $key")
      }
      logger.trace(s"Connecting to $key, $port")

      bs.connect(host, port)
    }
  }

  class PoolHandler(key: RequestKey, config: Http4sChannelPoolMap.Config)
      extends AbstractChannelPoolHandler {
    private val promises = collection.mutable.Queue[Http4sChannelPoolMap.Callback[F]]()

    private[netty] def dispatch(channel: Channel, request: Request[F]): Resource[F, Response[F]] = {
      val key = RequestKey.fromRequest(request)

      modelConversion
        .toNettyRequest(request)
        .evalMap { nettyRequest =>
          F.async[Resource[F, Response[F]]] { cb =>
            if (channel.eventLoop().inEventLoop) {
              safedispatch(channel, nettyRequest, key, cb)
            } else {
              channel.eventLoop().execute(() => safedispatch(channel, nettyRequest, key, cb))
            }
            // This is only used to cleanup if the resource is interrupted.
            F.pure(Some(F.delay(channel.close()).void))
          }
        }
        .flatMap(identity)
    }

    private def safedispatch(
        channel: Channel,
        request: HttpRequest,
        key: RequestKey,
        callback: Http4sChannelPoolMap.Callback[F]): Unit = void {
      // always enqueue
      promises.enqueue(callback)
      if (channel.isActive) {
        logger.trace(s"ch $channel: sending request to $key")
        // The voidPromise lets us receive failed-write signals from the
        // exceptionCaught method.
        channel.writeAndFlush(request, channel.voidPromise)
        logger.trace(s"ch $channel: after request to $key")
      } else {
        // make sure we call all enqueued promises
        logger.info(s"ch $channel: message dispatched by closed channel to destination $key.")
        promises.foreach(cb => cb(Left(new ClosedChannelException)))
        promises.clear()
        channel.close()
      }
    }

    override def channelAcquired(ch: Channel): Unit =
      logger.trace(s"Connected to $ch")

    override def channelCreated(ch: Channel): Unit = void {
      logger.trace(s"Created $ch")
      buildPipeline(ch)
    }

    override def channelReleased(ch: Channel): Unit =
      logger.trace(s"Releasing $ch")

    private def buildPipeline(channel: Channel) = {
      val pipeline = channel.pipeline()
      config.proxy.foreach {
        case p: HttpProxy =>
          p.toProxyHandler(key).foreach { handler =>
            void(pipeline.addLast("proxy", handler))
          }
        case s: Socks =>
          void(pipeline.addLast("proxy", s.toProxyHandler))
      }
      (key, SSLContextOption.toMaybeSSLContext(config.sslConfig)) match {
        case (RequestKey(Scheme.https, Uri.Authority(_, host, mayBePort)), Some(context)) =>
          void {
            logger.trace("Creating SSL engine")

            val port = mayBePort.getOrElse(443)
            val engine = context.createSSLEngine(host.value, port)
            val params = TLSParameters(endpointIdentificationAlgorithm = Some("HTTPS"))
            engine.setUseClientMode(true)
            engine.setSSLParameters(params.toSSLParameters)
            pipeline.addLast("ssl", new SslHandler(engine))
          }
        case _ => ()
      }

      pipeline.addLast(
        "httpClientCodec",
        new HttpClientCodec(
          config.maxInitialLength,
          config.maxHeaderSize,
          config.maxChunkSize,
          false))
      pipeline.addLast("streaming-handler", new HttpStreamsClientHandler)

      if (config.idleTimeout.isFinite && config.idleTimeout.length > 0) {
        pipeline
          .addLast(
            "timeout",
            new IdleStateHandler(0, 0, config.idleTimeout.length, config.idleTimeout.unit))
      }
      pipeline.addLast("http4s", new Http4sHandler[F](dispatcher, promises))
    }
  }
}

private[client] object Http4sChannelPoolMap {
  type Callback[F[_]] = Either[Throwable, Resource[F, Response[F]]] => Unit

  final case class Config(
      maxInitialLength: Int,
      maxHeaderSize: Int,
      maxChunkSize: Int,
      maxConnections: Int,
      idleTimeout: Duration,
      proxy: Option[Proxy],
      sslConfig: SSLContextOption)

  def fromFuture[F[_]: Async, A](future: => Future[A]): F[A] =
    Async[F].async_ { callback =>
      void(
        future
          .addListener((f: Future[A]) =>
            if (f.isSuccess) callback(Right(f.getNow)) else callback(Left(f.cause()))))
    }
}
