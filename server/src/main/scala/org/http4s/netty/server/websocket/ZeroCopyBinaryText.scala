/*
 * Copyright 2020 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s.netty.server.websocket

import org.http4s.websocket.WebSocketFrame
import scodec.bits.ByteVector

import java.nio.charset.StandardCharsets

final case class ZeroCopyBinaryText(data: ByteVector, last: Boolean) extends WebSocketFrame {

  lazy val str: String = new String(data.toArray, StandardCharsets.UTF_8)

  override val opcode: Int = 0x1

  override def toString: String = s"ZeroCopyBinaryText('$str', last: $last)"
}

object ZeroCopyBinaryText {
  def unsafe(data: Array[Byte], last: Boolean) = ZeroCopyBinaryText(ByteVector.view(data), last)
}
