package org.http4s.netty.client

import java.net.ConnectException

import cats.implicits._
import cats.effect.{Async, Resource}
import com.typesafe.netty.http.HttpStreamsClientHandler
import fs2.io.net.tls.TLSParameters
import io.netty.bootstrap.Bootstrap
import io.netty.channel.{Channel, ChannelFuture}
import io.netty.channel.pool.{
  AbstractChannelPoolHandler,
  AbstractChannelPoolMap,
  ChannelPoolHandler,
  FixedChannelPool
}
import io.netty.handler.codec.http.{HttpRequestEncoder, HttpResponseDecoder}
import io.netty.handler.ssl.SslHandler
import io.netty.handler.timeout.IdleStateHandler
import io.netty.util.AttributeKey
import io.netty.util.concurrent.Future
import org.http4s.Uri
import org.http4s.Uri.Scheme
import org.http4s.client.RequestKey

import scala.concurrent.duration.Duration

class Http4sChannelPoolMap[F[_]: Async](bootstrap: Bootstrap, config: Http4sChannelPoolMap.Config)
    extends AbstractChannelPoolMap[RequestKey, FixedChannelPool] {
  private[this] val logger = org.log4s.getLogger

  def resource(key: RequestKey): Resource[F, Channel] = {
    val pool = get(key)

    Resource
      .make(Http4sChannelPoolMap.fromFuture(pool.acquire())) { channel =>
        Http4sChannelPoolMap.fromFuture(pool.release(channel)).void
      }
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

    override def channelCreated(ch: Channel): Unit = {
      logger.trace(s"Created $ch")
      ch.attr(Http4sChannelPoolMap.attr).set(key)
      buildPipeline(ch)
      ()
    }

    override def channelReleased(ch: Channel): Unit =
      logger.trace(s"Releasing $ch")

    private def buildPipeline(channel: Channel) = {
      val pipeline = channel.pipeline()
      (key, NettyClientBuilder.SSLContextOption.toMaybeSSLContext(config.sslConfig)) match {
        case (RequestKey(Scheme.https, Uri.Authority(_, host, mayBePort)), Some(context)) =>
          logger.trace("Creating SSL engine")

          val port = mayBePort.getOrElse(443)
          val engine = context.createSSLEngine(host.value, port)
          val params = TLSParameters(endpointIdentificationAlgorithm = Some("HTTPS"))
          engine.setUseClientMode(true)
          engine.setSSLParameters(params.toSSLParameters)
          pipeline.addLast("ssl", new SslHandler(engine))
          ()
        case _ => ()
      }

      pipeline.addLast(
        "response-decoder",
        new HttpResponseDecoder(config.maxInitialLength, config.maxHeaderSize, config.maxChunkSize))
      pipeline.addLast("request-encoder", new HttpRequestEncoder)
      pipeline.addLast("streaming-handler", new HttpStreamsClientHandler)

      if (config.idleTimeout.isFinite && config.idleTimeout.length > 0)
        pipeline
          .addLast(
            "timeout",
            new IdleStateHandler(0, 0, config.idleTimeout.length, config.idleTimeout.unit))
    }
  }
}

object Http4sChannelPoolMap {
  val attr = AttributeKey.valueOf[RequestKey](classOf[RequestKey], "key")

  final case class Config(
      maxInitialLength: Int,
      maxHeaderSize: Int,
      maxChunkSize: Int,
      maxConnections: Int,
      idleTimeout: Duration,
      sslConfig: NettyClientBuilder.SSLContextOption)

  def fromFuture[F[_]: Async, A](future: => Future[A]): F[A] =
    Async[F].async_ { callback =>
      future
        .addListener((f: Future[A]) =>
          if (f.isSuccess) callback(Right(f.getNow)) else callback(Left(f.cause())))
      ()
    }
}
