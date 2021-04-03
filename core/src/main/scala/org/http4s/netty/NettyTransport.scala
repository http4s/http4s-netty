package org.http4s.netty

import java.nio.file.Path

sealed trait NettyTransport extends Product with Serializable

object NettyTransport {
  case object Nio extends NettyTransport
  case object Native extends NettyTransport
  case class UnixSocket(path: Path) extends NettyTransport
}
