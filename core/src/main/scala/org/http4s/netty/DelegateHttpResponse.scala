package org.http4s.netty

import io.netty.handler.codec.http.{HttpResponse, HttpResponseStatus, HttpVersion}

class DelegateHttpResponse(response: HttpResponse)
    extends DelegateHttpMessage[HttpResponse](response)
    with HttpResponse {
  override def setProtocolVersion(version: HttpVersion): HttpResponse = {
    underlying.setProtocolVersion(version)
    this
  }

  override def getStatus: HttpResponseStatus = status()

  override def status(): HttpResponseStatus = underlying.status()

  override def setStatus(status: HttpResponseStatus): HttpResponse = underlying.setStatus(status)
}
