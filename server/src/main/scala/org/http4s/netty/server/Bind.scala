package org.http4s.netty.server

import com.comcast.ip4s.{IpAddress, SocketAddress}

import java.nio.file.Path

sealed trait Bind
object Bind {
  final case class Network(address: SocketAddress[IpAddress], forceNio: Boolean) extends Bind
  final case class NamedSocket(path: Path) extends Bind
}
