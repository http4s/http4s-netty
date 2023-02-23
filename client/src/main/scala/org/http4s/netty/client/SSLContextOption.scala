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

package org.http4s.netty.client

import io.netty.handler.ssl.ApplicationProtocolConfig
import io.netty.handler.ssl.ApplicationProtocolConfig.Protocol
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior
import io.netty.handler.ssl.ApplicationProtocolNames
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder

import scala.util.control.NonFatal

private[client] sealed trait SSLContextOption extends Product with Serializable

private[client] object SSLContextOption {
  val defaultALPNConfig = new ApplicationProtocolConfig(
    Protocol.ALPN,
    SelectorFailureBehavior.NO_ADVERTISE,
    SelectedListenerFailureBehavior.ACCEPT,
    ApplicationProtocolNames.HTTP_2,
    ApplicationProtocolNames.HTTP_1_1
  )

  case object NoSSL extends SSLContextOption

  case object TryDefaultSSLContext extends SSLContextOption

  final case class Provided(sslContext: SslContext) extends SSLContextOption

  def toMaybeSSLContext(sco: SSLContextOption): Option[SslContext] =
    sco match {
      case SSLContextOption.NoSSL => None
      case SSLContextOption.TryDefaultSSLContext => tryDefaultSslContext
      case SSLContextOption.Provided(context) => Some(context)
    }

  def tryDefaultSslContext: Option[SslContext] =
    try
      Some(
        SslContextBuilder
          .forClient()
          .applicationProtocolConfig(defaultALPNConfig)
          .build())
    catch {
      case NonFatal(_) => None
    }
}
