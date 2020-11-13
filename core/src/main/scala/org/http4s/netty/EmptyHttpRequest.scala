package org.http4s.netty

import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.handler.codec.http._
import io.netty.util.ReferenceCountUtil

class EmptyHttpRequest(request: HttpRequest) extends DelegateHttpRequest(request) with FullHttpRequest {
  override def setProtocolVersion(version: HttpVersion): FullHttpRequest = {
    super.setProtocolVersion(version)
    this
  }

  override def setMethod(method: HttpMethod): FullHttpRequest = {
    super.setMethod(method)
    this
  }

  override def setUri(uri: String): FullHttpRequest = {
    super.setUri(uri)
    this
  }

  override def copy(): FullHttpRequest = underlying match {
    case f: FullHttpRequest => new EmptyHttpRequest(f.copy())
    case r =>
      val copy = new DefaultFullHttpRequest(r.protocolVersion(), r.method(), r.uri())
      copy.headers().add(headers())
      new EmptyHttpRequest(copy)
  }

  def withFull[B](f: FullHttpRequest => B, orElse: => B): B =
    underlying match {
      case full: FullHttpRequest => f(full)
      case _                     => orElse
    }

  override def duplicate(): FullHttpRequest = withFull(_.duplicate(), this)

  override def retainedDuplicate(): FullHttpRequest = withFull(_.retainedDuplicate(), this)

  override def replace(content: ByteBuf): FullHttpRequest = withFull(_.replace(content), this)

  override def retain(increment: Int): FullHttpRequest = {
    ReferenceCountUtil.retain(underlying, increment)
    this
  }

  override def retain(): FullHttpRequest = {
    ReferenceCountUtil.retain(underlying)
    this
  }

  override def touch(): FullHttpRequest = withFull(_.touch(), this)

  override def touch(hint: Any): FullHttpRequest = withFull(_.touch(hint), this)

  override def trailingHeaders(): HttpHeaders = new DefaultHttpHeaders()

  override def content(): ByteBuf = Unpooled.EMPTY_BUFFER

  override def refCnt(): Int = withFull(_.refCnt(), 1)

  override def release(): Boolean = ReferenceCountUtil.release(underlying)

  override def release(decrement: Int): Boolean = ReferenceCountUtil.release(underlying, decrement)
}
