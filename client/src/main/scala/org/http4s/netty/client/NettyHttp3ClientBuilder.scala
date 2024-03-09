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

package org.http4s
package netty
package client

import cats.effect.Async
import cats.effect.Deferred
import cats.effect.Resource
import cats.effect.implicits.*
import cats.effect.kernel.Ref
import cats.effect.std.Dispatcher
import cats.effect.std.Queue
import cats.syntax.all.*
import fs2.Chunk
import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http2.HttpConversionUtil
import io.netty.incubator.codec.http3.DefaultHttp3DataFrame
import io.netty.incubator.codec.http3.DefaultHttp3Headers
import io.netty.incubator.codec.http3.DefaultHttp3HeadersFrame
import io.netty.incubator.codec.http3.Http3
import io.netty.incubator.codec.http3.Http3ClientConnectionHandler
import io.netty.incubator.codec.http3.Http3DataFrame
import io.netty.incubator.codec.http3.Http3Exception
import io.netty.incubator.codec.http3.Http3Frame
import io.netty.incubator.codec.http3.Http3Headers
import io.netty.incubator.codec.http3.Http3HeadersFrame
import io.netty.incubator.codec.http3.Http3RequestStreamInboundHandler
import io.netty.incubator.codec.quic.QuicChannel
import io.netty.incubator.codec.quic.QuicException
import io.netty.incubator.codec.quic.QuicSslContextBuilder
import io.netty.incubator.codec.quic.QuicStreamChannel
import io.netty.util.ReferenceCountUtil
import org.http4s.client.Client
import org.http4s.client.RequestKey
import org.http4s.headers.`User-Agent`
import org.typelevel.ci.CIString

import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import javax.net.ssl.TrustManagerFactory
import scala.concurrent.duration.*

class NettyHttp3ClientBuilder[F[_]](
    headerTimeout: Duration,
    idleTimeout: Duration,
    eventLoopThreads: Int,
    maxInitialLength: Long,
    initialMaxStreamDataBidirectionalLocal: Long,
    // maxConnectionsPerKey: Int,
    transport: NettyTransport,
    trustManager: Option[TrustManagerFactory],
    nettyChannelOptions: NettyChannelOptions,
    defaultRequestHeaders: Headers
)(implicit F: Async[F]) {
  type Self = NettyHttp3ClientBuilder[F]

  private def copy(
      headerTimeout: Duration = idleTimeout,
      idleTimeout: Duration = idleTimeout,
      eventLoopThreads: Int = eventLoopThreads,
      maxInitialLength: Long = maxInitialLength,
      initialMaxStreamDataBidirectionalLocal: Long = initialMaxStreamDataBidirectionalLocal,
      // maxConnectionsPerKey: Int = maxConnectionsPerKey,
      transport: NettyTransport = transport,
      trustManager: Option[TrustManagerFactory] = trustManager,
      nettyChannelOptions: NettyChannelOptions = nettyChannelOptions,
      defaultRequestHeaders: Headers = defaultRequestHeaders
  ): NettyHttp3ClientBuilder[F] =
    new NettyHttp3ClientBuilder[F](
      headerTimeout,
      idleTimeout,
      eventLoopThreads,
      maxInitialLength,
      initialMaxStreamDataBidirectionalLocal,
      // maxConnectionsPerKey,
      transport,
      trustManager,
      nettyChannelOptions,
      defaultRequestHeaders
    )

  def withNativeTransport: Self = copy(transport = NettyTransport.defaultFor(Os.get))
  def withNioTransport: Self = copy(transport = NettyTransport.Nio)
  def withTransport(transport: NettyTransport): Self = copy(transport = transport)
  def withMaxInitialLength(size: Long): Self = copy(maxInitialLength = size)
  // def withMaxHeaderSize(size: Int): Self = copy(maxHeaderSize = size)
  def withInitialMaxStreamDataBidirectionalLocal(size: Long): Self =
    copy(initialMaxStreamDataBidirectionalLocal = size)
  // def withMaxConnectionsPerKey(size: Int): Self = copy(maxConnectionsPerKey = size)
  def withIdleTimeout(duration: FiniteDuration): Self = copy(idleTimeout = duration)

  def withDefaultTrustManager: Self =
    copy(trustManager = None)
  def withTrustManager(factory: TrustManagerFactory): Self =
    copy(trustManager = Some(factory))

  def withNettyChannelOptions(opts: NettyChannelOptions): Self =
    copy(nettyChannelOptions = opts)

  /** Socket selector threads.
    * @param nThreads
    *   number of selector threads. Use <code>0</code> for netty default
    * @return
    *   an updated builder
    */
  def withEventLoopThreads(nThreads: Int): Self = copy(eventLoopThreads = nThreads)

  def withUserAgent(useragent: `User-Agent`): Self =
    copy(defaultRequestHeaders = defaultRequestHeaders.put(useragent))

  def withDefaultRequestHeaders(headers: Headers): Self =
    copy(defaultRequestHeaders = headers)

  private def createBootstrap: Resource[F, Bootstrap] =
    Resource.make(F.delay {
      val bootstrap = new Bootstrap()
      EventLoopHolder.fromUdpTransport(transport, eventLoopThreads).configure(bootstrap)

      nettyChannelOptions.foldLeft(bootstrap) { case (boot, (opt, value)) =>
        boot.option(opt, value)
      }
      bootstrap
    })(bs => F.delay(bs.config().group().shutdownGracefully()).liftToF)

  def resource: Resource[F, Client[F]] = for {
    bs <- createBootstrap
    channel <- {

      val context = QuicSslContextBuilder
        .forClient()
        .applicationProtocols(Http3.supportedApplicationProtocols() *)
        .trustManager(trustManager.orNull)
        .build()

      val codec = Http3
        .newQuicClientCodecBuilder()
        .sslContext(context)
        .maxIdleTimeout(idleTimeout.toMillis, TimeUnit.MILLISECONDS)
        .initialMaxData(maxInitialLength)
        .initialMaxStreamDataBidirectionalLocal(initialMaxStreamDataBidirectionalLocal)
        .build()
      channelResource(F.delay(bs.handler(codec).bind(0)).liftToFWithChannel)
    }
  } yield Client(run(channel))

  def run(channel: Channel)(request: Request[F]): Resource[F, Response[F]] = {
    val key = RequestKey.fromRequest(request)
    // if not https fail with error
    // if not http/3 fail with error
    // connection pooling?
    val host = key.authority.host.value
    val port = key.authority.port.getOrElse(443)

    for {
      quickChannel <- channelResource(
        F.delay(
          QuicChannel
            .newBootstrap(channel)
            .handler(new Http3ClientConnectionHandler())
            .remoteAddress(new InetSocketAddress(host, port))
            .connect()
        ).liftToFA)
      deferred <- Resource.eval(Deferred[F, Either[Throwable, Response[fs2.Pure]]])
      trailers <- Resource.eval(Ref.of[F, Headers](Headers.empty))
      queue <- Resource.eval(fs2.concurrent.Channel.unbounded[F, Chunk[Byte]])
      dispatcher <- Dispatcher.parallel(true)
      stream <- channelResource(
        F.delay(
          Http3.newRequestStream(
            quickChannel,
            new NettyHttp3ClientBuilder.Http3Handler[F](dispatcher, deferred, queue, trailers)
          ))
          .liftToFA)
      _ <- Resource.eval(writeRequestF(stream, request, key))
      response <- Resource.eval(deferred.get.timeout(headerTimeout).flatMap(F.fromEither))
    } yield response
      .covary[F]
      .withBodyStream(queue.stream.flatMap(fs2.Stream.chunk))
  }

  private def channelResource[C <: Channel](acquire: F[C]): Resource[F, C] =
    Resource.make(acquire)(ch => F.delay(ch.close()).liftToF)

  private def writeRequestF(stream: QuicStreamChannel, request: Request[F], key: RequestKey) = {
    val host = key.authority.host.value
    val port = key.authority.port.getOrElse(443)

    val headerFrame = {
      val frame = new DefaultHttp3HeadersFrame(
        toHttp3Headers(request.headers)(
          _.method(request.method.name)
            .path(request.uri.toOriginForm.renderString)
            .authority(s"${host}:${port}")
            .scheme(key.scheme.value)))

      writeFrame(frame, stream, key)
    }

    val body = if (!NettyModelConversion.notAllowedWithBody.contains(request.method)) {
      val trailers = request.trailerHeaders.flatMap { h =>
        val frame = new DefaultHttp3HeadersFrame(toHttp3Headers(h)(identity))
        writeFrame(frame, stream, key)
      }

      request.body.chunks
        .map { chunk =>
          new DefaultHttp3DataFrame(NettyModelConversion.chunkToBytebuf(chunk))
        }
        .evalMap(frame => writeFrame(frame, stream, key))
        .compile
        .drain >> trailers
    } else F.unit

    headerFrame >> body
  }

  private def writeFrame(frame: Http3Frame, stream: QuicStreamChannel, key: RequestKey) =
    F.async[Unit] { cb =>
      cb(Right(Http4sHandler.writeInEventLoop(frame, stream, key)((_, err) => cb(Left(err)))))
      F.pure(Some(F.unit))
    }

  private def toHttp3Headers(headers: Headers)(modify: Http3Headers => Http3Headers) = {
    val nettyHeaders = new DefaultHttp3Headers()
    headers.foreach(r => void(nettyHeaders.set(r.name.toString.toLowerCase(), r.value)))
    modify(nettyHeaders)
  }
}

object NettyHttp3ClientBuilder {
  def apply[F[_]](implicit F: Async[F]): NettyHttp3ClientBuilder[F] =
    new NettyHttp3ClientBuilder[F](
      headerTimeout = 5.seconds,
      maxInitialLength = 10000000,
      initialMaxStreamDataBidirectionalLocal = 10000000,
      // maxConnectionsPerKey = 3,
      idleTimeout = 60.seconds,
      eventLoopThreads = 0,
      transport = NettyTransport.defaultFor(Os.get),
      trustManager = None,
      nettyChannelOptions = NettyChannelOptions.empty,
      defaultRequestHeaders = Headers()
    )

  private[NettyHttp3ClientBuilder] class Http3Handler[F[_]: Async](
      dispatcher: Dispatcher[F],
      headersDeferred: Deferred[F, Either[Throwable, Response[fs2.Pure]]],
      fs2Channel: fs2.concurrent.Channel[F, Chunk[Byte]],
      trailers: Ref[F, Headers])
      extends Http3RequestStreamInboundHandler {
    override def isSharable: Boolean = false

    private var inboundTranslationInProgress: Boolean = false

    private def toHeaders(headers: Http3Headers): Headers = {
      val buffer = List.newBuilder[Header.Raw]
      val iterator = headers.iterator

      iterator.forEachRemaining { e =>
        val key = e.getKey
        if (!Http3Headers.PseudoHeaderName.isPseudoHeader(key)) {
          buffer += Header.Raw(CIString(key.toString), e.getValue.toString)
        }
      }
      Headers(buffer.result())
    }

    override def channelRead(ctx: ChannelHandlerContext, frame: Http3HeadersFrame): Unit = {
      val headers = frame.headers()
      val headerStatus = headers.status()
      val methodFromHeaders = headers.method()

      val ioAction =
        if (headerStatus == null && methodFromHeaders == null) {
          inboundTranslationInProgress = false
          fs2Channel.close >> trailers.set(toHeaders(headers))
        } else {
          val status = HttpConversionUtil.parseStatus(headerStatus)

          val myHeaders = toHeaders(headers)
          inboundTranslationInProgress = true
          headersDeferred
            .complete(
              Right(
                Response[fs2.Pure](
                  Status.fromInt(status.code()).toOption.get,
                  HttpVersion.`HTTP/3`,
                  myHeaders)))
            .void
        }

      dispatcher.unsafeRunAndForget(ioAction)
      ReferenceCountUtil.safeRelease(frame)
    }

    override def handleQuicException(ctx: ChannelHandlerContext, exception: QuicException): Unit =
      void {
        dispatcher.unsafeRunAndForget(headersDeferred.complete(Left(new IllegalStateException())))
        ctx.close()
      }

    override def handleHttp3Exception(ctx: ChannelHandlerContext, exception: Http3Exception): Unit =
      void {
        dispatcher.unsafeRunAndForget(headersDeferred.complete(Left(new IllegalStateException())))
        ctx.close()
      }

    override def channelRead(ctx: ChannelHandlerContext, frame: Http3DataFrame): Unit = {
      inboundTranslationInProgress = true
      dispatcher
        .unsafeRunAndForget(
          fs2Channel.send(
            Chunk.array(NettyModelConversion.bytebufToArray(frame.content(), release = false))))
      ReferenceCountUtil.safeRelease(frame)
    }

    override def channelInputClosed(ctx: ChannelHandlerContext): Unit = void {
      if (inboundTranslationInProgress) {
        dispatcher.unsafeRunAndForget(fs2Channel.close)
      } else {
        dispatcher.unsafeRunAndForget(headersDeferred.complete(Left(new IllegalStateException())))
        ctx.close()
      }
    }

    override def channelInactive(ctx: ChannelHandlerContext): Unit = {
      if (inboundTranslationInProgress) {
        dispatcher.unsafeRunAndForget(fs2Channel.close)
      }
      super.channelInactive(ctx)
    }
  }
}
