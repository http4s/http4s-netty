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

import cats.syntax.all._
import com.comcast.ip4s._
import io.netty.handler.proxy.{
  HttpProxyHandler,
  ProxyHandler,
  Socks4ProxyHandler,
  Socks5ProxyHandler
}
import org.http4s.{BasicCredentials, Uri}
import org.http4s.client.RequestKey

import java.net.InetSocketAddress
import java.util.regex.Pattern.quote
import scala.util.Properties
import scala.util.matching.Regex

sealed trait Proxy

sealed trait Socks extends Proxy {
  private[client] def toProxyHandler: ProxyHandler
}
final case class Socks5(host: Host, port: Port, username: Option[String], password: Option[String])
    extends Socks {
  override private[client] def toProxyHandler: ProxyHandler = new Socks5ProxyHandler(
    new InetSocketAddress(host.show, port.value),
    username.orNull,
    password.orNull
  )
}
final case class Socks4(host: Host, port: Port, username: Option[String]) extends Socks {
  override private[client] def toProxyHandler: ProxyHandler = new Socks4ProxyHandler(
    new InetSocketAddress(host.show, port.value),
    username.orNull
  )
}

final case class IgnoredHosts private (regex: Regex) {
  def ignored(uri: RequestKey) =
    regex.pattern.matcher(uri.authority.host.value).matches()
}

object IgnoredHosts {
  val default = fromString("localhost|127.*|[::1]").get

  def fromString(mask: String) = {
    def disjunctToRegex(disjunct: String) = disjunct.trim match {
      case "*" => ".*"
      case s if s.startsWith("*") && s.endsWith("*") =>
        ".*" + quote(s.substring(1, s.length - 1)) + ".*"
      case s if s.startsWith("*") =>
        ".*" + quote(s.substring(1))
      case s if s.endsWith("*") =>
        quote(s.substring(0, s.length - 1)) + ".*"
      case s => quote(s)
    }

    val joined = mask
      .split("\\|")
      .filterNot(_.trim.isEmpty)
      .map(disjunct => disjunctToRegex(disjunct.toLowerCase))
      .mkString("|")
    if (joined.nonEmpty) IgnoredHosts(joined.r).some else none
  }

}

final case class HttpProxy(
    scheme: Uri.Scheme,
    host: Host,
    port: Option[Port],
    ignoreHosts: IgnoredHosts,
    credentials: Option[BasicCredentials]
) extends Proxy {
  def defaultPort = if (scheme == Uri.Scheme.https) 443 else 80

  // todo: should we enforce we need to use https proxy for https requests?
  private[client] def toProxyHandler(key: RequestKey) = if (!ignoreHosts.ignored(key)) {
    credentials
      .fold(
        new HttpProxyHandler(
          new InetSocketAddress(host.show, port.map(_.value).getOrElse(defaultPort))
        )
      )(cred =>
        new HttpProxyHandler(
          new InetSocketAddress(host.show, port.map(_.value).getOrElse(defaultPort)),
          cred.username,
          cred.password
        ))
      .some
  } else none
}

object Proxy {

  /** https://docs.oracle.com/javase/8/docs/api/java/net/doc-files/net-properties.html#Proxies
    * @return
    *   a proxy instance if the system properties specified in the document above is detected
    */
  def fromSystemProperties: Option[Proxy] = {
    val http = {
      val httpProxy = sys.props.get("http.proxyHost").flatMap(Host.fromString) -> sys.props
        .get("http.proxyPort")
        .map(Port.fromString)
      val httpsProxy = sys.props.get("https.proxyHost").flatMap(Host.fromString) -> sys.props
        .get("https.proxyPort")
        .map(Port.fromString)
      val ignoreHosts =
        sys.props
          .get("http.nonProxyHosts")
          .flatMap(IgnoredHosts.fromString)
          .getOrElse(IgnoredHosts.default)

      httpProxy
        .mapN((host, port) => HttpProxy(Uri.Scheme.http, host, port, ignoreHosts, None))
        .orElse(
          httpsProxy.mapN((host, port) =>
            HttpProxy(Uri.Scheme.https, host, port, ignoreHosts, None))
        )
    }

    val socks = {
      val socksVersion = sys.props.getOrElse("socksProxyVersion", "5")
      val socksHost = sys.props.get("socksProxyHost").flatMap(Host.fromString)
      val socksPort = Port.fromString(sys.props.getOrElse("socksProxyPort", "1080"))

      val socksUsername =
        sys.props.getOrElse("java.net.socks.username", Properties.userName)
      val socksPassword = sys.props.get("java.net.socks.password")

      socksVersion match {
        case "4" =>
          (socksHost, socksPort).mapN((h, p) => Socks4(h, p, Some(socksUsername)))
        case _ =>
          (socksHost, socksPort).mapN((h, p) => Socks5(h, p, Some(socksUsername), socksPassword))
      }
    }

    http.orElse(socks)
  }

}
