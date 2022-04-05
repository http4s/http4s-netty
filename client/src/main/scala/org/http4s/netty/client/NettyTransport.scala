package org.http4s.netty.client

import java.nio.file.Path

sealed trait NettyTransport extends Product with Serializable

object NettyTransport {
  case object Nio extends NettyTransport
  case object Native extends NettyTransport
  final case class NamedSocket(path: Path) extends NettyTransport
}
