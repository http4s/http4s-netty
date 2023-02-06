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
import io.netty.channel.pool.ChannelPoolHandler
import io.netty.channel.pool.FixedChannelPool
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.ssl.SslHandler
import io.netty.handler.timeout.IdleStateHandler
import io.netty.util.AttributeKey
import io.netty.util.concurrent.Future
import org.http4s.Request
import org.http4s.Response
import org.http4s.Uri
import org.http4s.Uri.Scheme
import org.http4s.client.RequestKey

import java.net.ConnectException
import scala.concurrent.duration.Duration

private[client] class Http4sChannelPoolMap[F[_]: Async](
    bootstrap: Bootstrap,
    config: Http4sChannelPoolMap.Config,
    dispatcher: Dispatcher[F]
) extends AbstractChannelPoolMap[RequestKey, FixedChannelPool] {
  private[this] val logger = org.log4s.getLogger

  def run(request: Request[F]): Resource[F, Response[F]] = {
    val key = RequestKey.fromRequest(request)
    val pool = get(key)

    Resource
      .make(Http4sChannelPoolMap.fromFuture(pool.acquire())) { channel =>
        Http4sChannelPoolMap.fromFuture(pool.release(channel)).void
      }
      .flatMap(_.pipeline().get(classOf[Http4sHandler[F]]).dispatch(request))
  }

  override def newPool(key: RequestKey): FixedChannelPool =
    new MyFixedChannelPool(
      bootstrap,
      new WrappedChannelPoolHandler(key, config),
      config.maxConnections,
      key)

  class MyFixedChannelPool(
      bs: Bootstrap,
      handler: ChannelPoolHandler,
      maxConnections: Int,
      key: RequestKey)
      extends FixedChannelPool(bs, handler, maxConnections) {
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

  class WrappedChannelPoolHandler(
      key: RequestKey,
      config: Http4sChannelPoolMap.Config
  ) extends AbstractChannelPoolHandler {
    override def channelAcquired(ch: Channel): Unit = {
      logger.trace(s"Connected to $ch")
      ch.attr(Http4sChannelPoolMap.attr).set(key)
    }

    override def channelCreated(ch: Channel): Unit = void {
      logger.trace(s"Created $ch")
      ch.attr(Http4sChannelPoolMap.attr).set(key)
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
      pipeline.addLast("http4s", new Http4sHandler[F](dispatcher))
    }
  }
}

private[client] object Http4sChannelPoolMap {
  val attr: AttributeKey[RequestKey] = AttributeKey.valueOf[RequestKey](classOf[RequestKey], "key")

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
