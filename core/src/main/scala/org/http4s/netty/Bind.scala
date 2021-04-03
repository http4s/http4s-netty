package org.http4s.netty

import java.net.InetSocketAddress
import java.nio.file.Path

sealed trait Bind extends Product with Serializable

object Bind {
  case class Tcp(address: InetSocketAddress, forceNio: Boolean = false) extends Bind
  case class UnixSocket(path: Path) extends Bind
}
