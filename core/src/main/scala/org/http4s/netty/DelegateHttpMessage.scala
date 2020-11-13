package org.http4s.netty

import io.netty.handler.codec.DecoderResult
import io.netty.handler.codec.http.{HttpHeaders, HttpMessage, HttpVersion}

import scala.reflect.ClassTag

abstract class DelegateHttpMessage[M <: HttpMessage: ClassTag](protected val underlying: M) extends HttpMessage {
  override def getProtocolVersion: HttpVersion = underlying.protocolVersion

  override def protocolVersion: HttpVersion = underlying.protocolVersion

  override def headers: HttpHeaders = underlying.headers

  override def getDecoderResult: DecoderResult = underlying.decoderResult

  override def decoderResult: DecoderResult = underlying.decoderResult

  override def setDecoderResult(result: DecoderResult): Unit = {
    underlying.setDecoderResult(result)
  }

  override def toString: String = this.getClass.getName + "(" + underlying.toString + ")"
}
