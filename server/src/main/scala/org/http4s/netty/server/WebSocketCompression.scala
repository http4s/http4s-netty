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

package org.http4s.netty.server

import io.netty.handler.codec.compression.ZlibCodecFactory

/** Settings of WebSocket compression: the permessage-deflate extension (RFC 7692) and the older
  * deflate-frame extension, see [[NettyServerBuilder.withWebSocketCompression(compression*]].
  *
  * [[WebSocketCompression.default]] reproduces netty's `WebSocketServerCompressionHandler`.
  *
  * A `windowBits` or `memLevel` other than the defaults (15 and 8), or `allowServerWindowBits`,
  * needs JZlib (`com.jcraft:jzlib`) on the classpath: the JDK's zlib only supports the defaults.
  * The server fails to start otherwise.
  */
final class WebSocketCompression private (
    val compressionLevel: Int,
    val windowBits: Int,
    val memLevel: Int,
    val compressionThreshold: Int,
    val allowServerWindowBits: Boolean
) {

  /** zlib compression level, from 0 (no compression) to 9 (best compression). Default 6. */
  def withCompressionLevel(compressionLevel: Int): WebSocketCompression = {
    require(
      compressionLevel >= 0 && compressionLevel <= 9,
      s"compressionLevel: $compressionLevel (expected: 0-9)")
    copy(compressionLevel = compressionLevel)
  }

  /** Size of the server's compression window, `2^windowBits` bytes, from 9 to 15. Default 15 (32
    * KiB). Below 15 it is announced to the client as `server_max_window_bits`. Together with
    * `memLevel` it sets the memory each connection keeps for compression.
    */
  def withWindowBits(windowBits: Int): WebSocketCompression = {
    require(windowBits >= 9 && windowBits <= 15, s"windowBits: $windowBits (expected: 9-15)")
    copy(windowBits = windowBits)
  }

  /** zlib memory level, from 1 to 9: the size of the compressor's internal state. Default 8. */
  def withMemLevel(memLevel: Int): WebSocketCompression = {
    require(memLevel >= 1 && memLevel <= 9, s"memLevel: $memLevel (expected: 1-9)")
    copy(memLevel = memLevel)
  }

  /** Messages whose first frame is smaller than this many bytes are sent uncompressed, where
    * compressing would cost more than it saves. Default 0: every message is compressed.
    */
  def withCompressionThreshold(compressionThreshold: Int): WebSocketCompression = {
    require(
      compressionThreshold >= 0,
      s"compressionThreshold: $compressionThreshold (expected: >= 0)")
    copy(compressionThreshold = compressionThreshold)
  }

  /** Whether a client may choose the server's window size with `server_max_window_bits`, which can
    * lower it below `windowBits` but not raise it. Enabling it needs JZlib, since the window can
    * then differ from the default. Default: only when JZlib is on the classpath, as in netty. When
    * disabled, such an offer is declined and the connection runs uncompressed.
    */
  def withAllowServerWindowBits(allowServerWindowBits: Boolean): WebSocketCompression =
    copy(allowServerWindowBits = allowServerWindowBits)

  /** Whether these settings can need a window or memory level other than the JDK zlib's. */
  private[server] def requiresJZlib: Boolean =
    allowServerWindowBits ||
      windowBits != WebSocketCompression.DefaultWindowBits ||
      memLevel != WebSocketCompression.DefaultMemLevel

  private def copy(
      compressionLevel: Int = compressionLevel,
      windowBits: Int = windowBits,
      memLevel: Int = memLevel,
      compressionThreshold: Int = compressionThreshold,
      allowServerWindowBits: Boolean = allowServerWindowBits
  ): WebSocketCompression =
    new WebSocketCompression(
      compressionLevel,
      windowBits,
      memLevel,
      compressionThreshold,
      allowServerWindowBits
    )

  override def toString: String =
    s"WebSocketCompression(compressionLevel=$compressionLevel, windowBits=$windowBits, " +
      s"memLevel=$memLevel, compressionThreshold=$compressionThreshold, " +
      s"allowServerWindowBits=$allowServerWindowBits)"
}

object WebSocketCompression {
  private val DefaultWindowBits = 15
  private val DefaultMemLevel = 8

  /** netty's `WebSocketServerCompressionHandler` settings. */
  val default: WebSocketCompression = new WebSocketCompression(
    compressionLevel = 6,
    windowBits = DefaultWindowBits,
    memLevel = DefaultMemLevel,
    compressionThreshold = 0,
    allowServerWindowBits = ZlibCodecFactory.isSupportingWindowSizeAndMemLevel
  )
}
