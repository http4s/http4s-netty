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

import io.netty.channel.ChannelOption

/** Ensure we construct our netty channel options in a typeful, immutable way, despite the
  * underlying being disgusting
  */
sealed abstract class NettyChannelOptions {

  /** Prepend to the channel options * */
  def prepend[O](channelOption: ChannelOption[O], value: O): NettyChannelOptions

  /** Append to the channel options * */
  def append[O](channelOption: ChannelOption[O], value: O): NettyChannelOptions

  /** Remove a channel option, if present * */
  def remove[O](channelOption: ChannelOption[O]): NettyChannelOptions

  private[http4s] def foldLeft[O](initial: O)(f: (O, (ChannelOption[Any], Any)) => O): O
}

object NettyChannelOptions {
  val empty = new NettyCOptions(Vector.empty)
}

private[http4s] final class NettyCOptions(
    private[http4s] val underlying: Vector[(ChannelOption[Any], Any)])
    extends NettyChannelOptions {

  def prepend[O](channelOption: ChannelOption[O], value: O): NettyChannelOptions =
    new NettyCOptions((channelOption.asInstanceOf[ChannelOption[Any]], value: Any) +: underlying)

  def append[O](channelOption: ChannelOption[O], value: O): NettyChannelOptions =
    new NettyCOptions(underlying :+ ((channelOption.asInstanceOf[ChannelOption[Any]], value: Any)))

  def remove[O](channelOption: ChannelOption[O]): NettyChannelOptions =
    new NettyCOptions(underlying.filterNot(_._1 == channelOption))

  private[http4s] def foldLeft[O](initial: O)(f: (O, (ChannelOption[Any], Any)) => O) =
    underlying.foldLeft[O](initial)(f)
}
