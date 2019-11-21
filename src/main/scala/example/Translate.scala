package example

import cats.syntax.show._
import cats.effect.{ConcurrentEffect, Sync}
import com.typesafe.netty.http.{DefaultStreamedHttpResponse, StreamedHttpRequest}
import io.netty.buffer.{ByteBuf, ByteBufAllocator}
import io.netty.handler.codec.http.{DefaultHttpContent, DefaultHttpHeaders, FullHttpRequest, HttpContent, HttpRequest, HttpResponse, HttpResponseStatus, DefaultFullHttpResponse => NettyFullResponse, HttpVersion => NettyHttpVersion}
import fs2.Stream
import fs2.interop.reactivestreams._
import org.http4s
import org.http4s._
import org.http4s.util.StringWriter

import scala.collection.JavaConverters._
import scala.reflect.ClassTag

object Translate {
  def toHttp4sRequest[F[_]: ConcurrentEffect](
      request: HttpRequest
  )(implicit tag: ClassTag[HttpRequest]): ParseResult[Request[F]] = {
    val headers = {
      val builder = List.newBuilder[Header]
      request
        .headers()
        .asScala
        .foreach(e => builder += Header(e.getKey, e.getValue))
      Headers(builder.result())
    }
    val body = request match {
      case r: FullHttpRequest =>
        Right(Stream.chunk(BytebufChunk(r.content())))

      case r: StreamedHttpRequest =>
        Right(
          r.toStream()
            .flatMap(c => Stream.chunk(BytebufChunk(c.content())))
        )
      case _ =>
        Left(ParseFailure("Unsupported request type", s"${tag.runtimeClass}"))
    }

    for {
      m     <- Method.fromString(request.method().name())
      u     <- Uri.fromString(request.uri())
      proto = request.protocolVersion()
      v     <- HttpVersion.fromVersion(proto.majorVersion(), proto.minorVersion())
      b     <- body
    } yield Request[F](m, u, v, headers, b)
  }

  def toNettyResponse[F[_]: ConcurrentEffect](now: HttpDate,
      response: Response[F]
  ): HttpResponse = {

    val version = NettyHttpVersion.valueOf(response.httpVersion.show)
    val status =
      HttpResponseStatus.valueOf(response.status.code, response.status.reason)
    val headers = {
      val heads = new DefaultHttpHeaders()
      response.headers.foreach { h =>
        heads.add(h.name.value, h.value)
        ()
      }
      heads.add("Date", {
        val s = new StringWriter()
        now.render(s)
        s.result
      })
      heads
    }

    response.body match {
      case http4s.EmptyBody =>
        val res = new NettyFullResponse(version, status)
        res.headers().add(headers)
        res
      case stream =>
        val res = new DefaultStreamedHttpResponse(
          version,
          status,
          stream.chunks
            .evalMap[F, HttpContent] {
              case chnk: BytebufChunk =>
                Sync[F].delay(new DefaultHttpContent(chnk.buf))
              case buf =>
                Sync[F].delay {
                  val buffer: ByteBuf =
                    ByteBufAllocator.DEFAULT.ioBuffer(buf.size)
                  buffer.writeBytes(buf.toArray)
                  new DefaultHttpContent(buffer)
                }
            }
            .toUnicastPublisher()
        )
        res.headers().add(headers)
        res
    }
  }
}
