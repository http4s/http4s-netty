package org.http4s.netty

import io.netty.handler.codec.http.{HttpMethod, HttpRequest, HttpVersion}

class DelegateHttpRequest(request: HttpRequest) extends DelegateHttpMessage[HttpRequest](request) with HttpRequest {
  override def setProtocolVersion(version: HttpVersion): HttpRequest = {
    underlying.setProtocolVersion(version)
    this
  }

  override def getMethod: HttpMethod = method()

  override def method(): HttpMethod = underlying.method()

  override def setMethod(method: HttpMethod): HttpRequest = underlying.setMethod(method)

  override def getUri: String = uri()

  override def uri(): String = underlying.uri()

  override def setUri(uri: String): HttpRequest = underlying.setUri(uri)
}
