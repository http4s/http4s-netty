package org.http4s.netty

sealed trait NettyTransport extends Product with Serializable

object NettyTransport {
  case object Nio extends NettyTransport
  case object Native extends NettyTransport
}
