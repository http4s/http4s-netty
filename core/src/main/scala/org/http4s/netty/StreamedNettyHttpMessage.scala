package org.http4s.netty

import fs2.Stream
import io.netty.handler.codec.http._

sealed trait StreamedNettyHttpMessage[F[_]] extends HttpMessage {
  def body: Stream[F, Byte]
}

case class StreamedNettyHttpRequest[F[_]](
    version: HttpVersion,
    override val method: HttpMethod,
    override val uri: String,
    override val headers: HttpHeaders,
    body: Stream[F, Byte]
) extends DefaultHttpRequest(version, method, uri, headers)
    with StreamedNettyHttpMessage[F]

case class StreamedNettyHttpResponse[F[_]](
    version: HttpVersion,
    override val status: HttpResponseStatus,
    override val headers: HttpHeaders,
    body: Stream[F, Byte]
) extends DefaultHttpResponse(version, status, headers)
    with StreamedNettyHttpMessage[F]

case class DelegateStreamedHttpRequest[F[_]](request: HttpRequest, body: Stream[F, Byte])
    extends DelegateHttpRequest(request)
    with StreamedNettyHttpMessage[F]

case class DelegateStreamedHttpResponse[F[_]](response: HttpResponse, body: Stream[F, Byte])
    extends DelegateHttpResponse(response)
    with StreamedNettyHttpMessage[F]
