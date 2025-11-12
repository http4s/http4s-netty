package org.http4s.netty.server.websocket

import org.http4s.websocket.WebSocketFrame
import scodec.bits.ByteVector

import java.nio.charset.StandardCharsets

final case class ZeroCopyBinaryText(content: Array[Byte], last: Boolean) extends WebSocketFrame {

  override def data: ByteVector = ByteVector.view(content)

  lazy val str: String = new String(data.toArray, StandardCharsets.UTF_8)

  override val opcode: Int = 0x1

  override def toString: String = s"ZeroCopyBinaryText('$str', last: $last)"
}
