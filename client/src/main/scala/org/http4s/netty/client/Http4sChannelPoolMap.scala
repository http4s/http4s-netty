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
import cats.syntax.all._
import com.typesafe.netty.http.HttpStreamsClientHandler
import fs2.io.net.tls.TLSParameters
import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelPipeline
import io.netty.channel.pool.AbstractChannelPoolHandler
import io.netty.channel.pool.AbstractChannelPoolMap
import io.netty.channel.pool.ChannelPoolHandler
import io.netty.channel.pool.FixedChannelPool
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http2.Http2FrameCodecBuilder
import io.netty.handler.codec.http2.Http2MultiplexHandler
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec
import io.netty.handler.ssl.ApplicationProtocolNames
import io.netty.handler.ssl.SslHandler
import io.netty.handler.timeout.IdleStateHandler
import io.netty.util.concurrent.Future
import org.http4s.HttpVersion
import org.http4s.Request
import org.http4s.Response
import org.http4s.Uri
import org.http4s.Uri.Scheme
import org.http4s.client.RequestKey

import java.net.ConnectException
import scala.concurrent.duration.Duration

private[client] case class Key(requestKey: RequestKey, version: HttpVersion)

private[client] class Http4sChannelPoolMap[F[_]](
    bootstrap: Bootstrap,
    config: Http4sChannelPoolMap.Config
)(implicit F: Async[F])
    extends AbstractChannelPoolMap[Key, FixedChannelPool] {
  private[this] val logger = org.log4s.getLogger

  private def configure2(originalChannel: Channel) = {
    val streamChannelBootstrap = new Http2StreamChannelBootstrap(originalChannel)
    streamChannelBootstrap.handler(new ChannelInitializer[Channel] {
      override def initChannel(ch: Channel): Unit = void {
        val pipeline = ch.pipeline()
        pipeline.addLast(new Http2StreamFrameToHttpObjectCodec(false))
        endOfPipeline(pipeline)
      }
    })
    Resource.eval(F.delay(streamChannelBootstrap.open()).liftToFA)
  }

  private def endOfPipeline(pipeline: ChannelPipeline): Unit = void {
    logger.trace("building pipeline / end-of-pipeline")
    pipeline.addLast("streaming-handler", new HttpStreamsClientHandler)

    if (config.idleTimeout.isFinite && config.idleTimeout.length > 0) {
      void(
        pipeline
          .addLast(
            "timeout",
            new IdleStateHandler(0, 0, config.idleTimeout.length, config.idleTimeout.unit)))
    }
  }

  private def connectAndConfigure(key: Key): Resource[F, Channel] = {
    val pool = get(key)

    val connect = Resource.make(F.delay(pool.acquire()).liftToFA) { channel =>
      F.delay(pool.release(channel)).liftToF
    }

    Util.runInVersion(
      version = key.version,
      on2 = connect.flatMap(configure2),
      on1 = connect
    )
  }

  def run(request0: Request[F]): Resource[F, Response[F]] = {
    val request =
      if (config.http2 && request0.uri.scheme.contains(Uri.Scheme.https))
        request0.withHttpVersion(HttpVersion.`HTTP/2`)
      else request0
    val key = Key(RequestKey.fromRequest(request), request.httpVersion)

    for {
      channel <- connectAndConfigure(key)
      dispatcher <- Dispatcher.sequential[F](await = true)
      handler <- Resource.make {
        F.pure {
          val handler =
            new Http4sHandler[F](dispatcher)
          channel.pipeline().addLast("http4s", handler)
          handler
        }
      } { h =>
        val pipeline = channel.pipeline()
        F.delay(if (pipeline.toMap.containsKey("http4s")) void(pipeline.remove(h)) else ())
      }
      response <- handler.dispatch(request, channel, key)
    } yield response
  }

  override def newPool(key: Key): FixedChannelPool =
    new MyFixedChannelPool(
      bootstrap,
      new WrappedChannelPoolHandler(key, config),
      config.maxConnections,
      key.requestKey)

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
      key: Key,
      config: Http4sChannelPoolMap.Config
  ) extends AbstractChannelPoolHandler {
    override def channelAcquired(ch: Channel): Unit =
      logger.trace(s"Connected to $ch for ${key}")

    override def channelCreated(ch: Channel): Unit = void {
      logger.trace(s"Created $ch for ${key}")
      buildPipeline(ch)
    }

    override def channelReleased(ch: Channel): Unit =
      logger.trace(s"Releasing $ch ${key}")

    private def buildPipeline(channel: Channel) = {
      logger.trace(s"building pipeline for ${key}")
      val alpn = Util.runInVersion(
        key.version,
        // TODO: Should we include http/1.1 here or not
        Some(List(ApplicationProtocolNames.HTTP_2)),
        None
      )
      val pipeline = channel.pipeline()
      (key.requestKey, SSLContextOption.toMaybeSSLContext(config.sslConfig)) match {
        case (RequestKey(Scheme.https, Uri.Authority(_, host, mayBePort)), Some(context)) =>
          void {
            logger.trace("Creating SSL engine")

            val port = mayBePort.getOrElse(443)
            val engine = context.createSSLEngine(host.value, port)
            val params = TLSParameters(
              endpointIdentificationAlgorithm = Some("HTTPS"),
              applicationProtocols = alpn
            )
            engine.setUseClientMode(true)
            engine.setSSLParameters(params.toSSLParameters)
            pipeline.addLast("ssl", new SslHandler(engine))
          }
        case _ => ()
      }

      config.proxy.foreach {
        case p: HttpProxy =>
          p.toProxyHandler(key.requestKey).foreach { handler =>
            void(pipeline.addLast("proxy", handler))
          }
        case s: Socks =>
          void(pipeline.addLast("proxy", s.toProxyHandler))
      }

      configurePipeline(channel, key)
    }
  }

  private def configurePipeline(channel: Channel, key: Key) =
    Util.runInVersion(
      key.version,
      on2 = void {
        val pipeline = channel.pipeline()
        pipeline.addLast("codec", Http2FrameCodecBuilder.forClient().build())
        pipeline.addLast("multiplex", new Http2MultiplexHandler(UnsupportedHandler))
      },
      on1 = void {
        val pipeline = channel
          .pipeline()
        pipeline
          .addLast(
            "httpClientCodec",
            new HttpClientCodec(
              config.maxInitialLength,
              config.maxHeaderSize,
              config.maxChunkSize,
              false)
          )
        endOfPipeline(pipeline)
      }
    )
}

private[client] object Http4sChannelPoolMap {
  final case class Config(
      maxInitialLength: Int,
      maxHeaderSize: Int,
      maxChunkSize: Int,
      maxConnections: Int,
      idleTimeout: Duration,
      proxy: Option[Proxy],
      sslConfig: SSLContextOption,
      http2: Boolean
  )

  private[client] def fromFuture[F[_]: Async, A](future: => Future[A]): F[A] =
    Async[F].async { callback =>
      val fut = future
      void(
        fut
          .addListener((f: Future[A]) =>
            if (f.isSuccess) callback(Right(f.getNow)) else callback(Left(f.cause()))))
      Async[F].delay(Some(Async[F].delay(fut.cancel(false)).void))
    }
}
