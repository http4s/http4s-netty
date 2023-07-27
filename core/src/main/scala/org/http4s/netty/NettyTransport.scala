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

package org.http4s.netty

import java.util.Locale
import scala.util.Properties

sealed trait NettyTransport extends Product with Serializable

object NettyTransport {
  case object Nio extends NettyTransport
  sealed trait Native extends NettyTransport

  @deprecated("Use Auto instead of Native", since = "0.5.9")
  case object Native extends Native
  case object Auto extends Native
  case object IOUring extends Native
  case object Epoll extends Native
  case object KQueue extends Native

  def defaultFor(os: Os): NettyTransport =
    os match {
      case Os.Linux => Epoll
      case Os.Mac => KQueue
      case Os.Windows => Nio
      case Os.Other => Nio
    }
}

sealed trait Os extends Product with Serializable
object Os {
  def get: Os = {
    val osName = Properties.osName.toLowerCase(Locale.ENGLISH)
    if (osName.contains("mac") || osName.contains("darwin")) {
      Os.Mac
    } else if (osName.contains("win")) {
      Os.Windows
    } else if (osName.contains("nux")) {
      Os.Linux
    } else {
      Os.Other
    }
  }
  case object Linux extends Os
  case object Mac extends Os
  case object Windows extends Os
  case object Other extends Os
}
