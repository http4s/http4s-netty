package org.http4s.netty
package server

import java.time.Instant

import cats.implicits._
import cats.effect.Effect
import io.chrisdavenport.vault.Vault

import scala.jdk.CollectionConverters._
import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter}
import io.netty.handler.codec.http.{
  DefaultFullHttpResponse,
  DefaultHttpHeaders,
  HttpHeaderNames,
  HttpHeaderValues,
  HttpRequest,
  HttpResponseStatus,
  HttpVersion
}
import io.netty.handler.timeout.IdleStateEvent
import org.http4s.headers.Date
import org.http4s.server.ServiceErrorHandler
import org.http4s.{
  Header,
  Headers,
  HttpApp,
  HttpDate,
  Method,
  ParseResult,
  Request,
  Response,
  Uri,
  HttpVersion => HV
}

class Http4sNettyHandler2[F[_]](httpApp: HttpApp[F], serviceErrorHandler: ServiceErrorHandler[F])(
    implicit F: Effect[F])
    extends ChannelInboundHandlerAdapter {

  def convertRequest(request: HttpRequest with StreamedNettyHttpMessage[F]): F[Request[F]] = {
    val uri: ParseResult[Uri] = Uri.fromString(request.uri)
    val entries = request.headers.entries().asScala
    val headers = entries.map(e => Header(e.getKey, e.getValue)).toList

    val method: ParseResult[Method] =
      Method.fromString(request.method.name())
    val version: ParseResult[HV] = HV.fromString(request.protocolVersion().text())

    val either = for {
      v <- version
      u <- uri
      m <- method
    } yield Request[F](
      m,
      u,
      v,
      Headers(headers),
      request.body,
      Vault.empty
    )
    F.fromEither(either)
  }

  def responseWithEntity(response: Response[F]) = {
    val headers = new DefaultHttpHeaders()
    response.headers.foreach(h => discard(headers.add(h.name, h.value)))

    StreamedNettyHttpResponse[F](
      HttpVersion.HTTP_1_1,
      HttpResponseStatus.valueOf(response.status.code),
      headers,
      response.body)
  }

  def toEmptyResponse(response: Response[F], isHead: Boolean) = {
    val r = new DefaultFullHttpResponse(
      HttpVersion.HTTP_1_1,
      HttpResponseStatus.valueOf(response.status.code))
    val headers = new DefaultHttpHeaders()
    response.headers.foreach(h => discard(headers.add(h.name, h.value)))
    if (isHead) {
      val contentLength = response.contentLength
      if (response.isChunked) {
        r.headers().add(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
      }
      contentLength.foreach(length => r.headers().add(HttpHeaderNames.CONTENT_LENGTH, length))
    }
    r
  }

  def convertResponse(request: Request[F], response: Response[F]) = {
    val converted = if (response.status.isEntityAllowed && request.method != Method.HEAD) {
      responseWithEntity(response)
    } else {
      toEmptyResponse(response, request.method == Method.HEAD)
    }
    org.http4s.headers.Connection.from(request.headers) match {
      case Some(conn) => converted.headers().add(HttpHeaderNames.CONNECTION, conn.value)
      case None if request.httpVersion.minor == 0 =>
        converted.headers().add(HttpHeaderNames.CONNECTION, "close")
      case _ =>
    }
    converted
  }

  override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit =
    msg match {
      case msg: HttpRequest with StreamedNettyHttpMessage[F] =>
        val action = for {
          req <- convertRequest(msg)
          res <- F.suspend(httpApp(req)).recoverWith(serviceErrorHandler(req))
        } yield convertResponse(req, res.putHeaders(Date(HttpDate.unsafeFromInstant(Instant.now))))
        ctx.unsafeRunSync(action.flatMap(res => ctx.delay(ctx.writeAndFlush(res)).void))
      case _ => println("unknown message: " + msg)
    }

  def discard[A](a: => A): Unit = {
    val _ = a
    ()
  }

  override def channelActive(ctx: ChannelHandlerContext): Unit = {
    // AUTO_READ is off, so need to do the first read explicitly.
    // this method is called when the channel is registered with the event loop,
    // so ctx.read is automatically safe here w/o needing an isRegistered().
    ctx.read(); ()
  }

  override def userEventTriggered(ctx: ChannelHandlerContext, evt: scala.Any): Unit =
    evt match {
      case _: IdleStateEvent if ctx.channel().isOpen =>
        println("Closing connection due to idle timeout")
        ctx.close(); ()
      case _ => super.userEventTriggered(ctx, evt)
    }
}
