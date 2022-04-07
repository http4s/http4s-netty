package org.http4s.netty.client

import com.comcast.ip4s._
import munit.FunSuite
import org.http4s.Uri
import org.http4s.client.RequestKey

class IgnoredHostsTest extends FunSuite {
  def make(host: Uri.Host) = RequestKey(Uri.Scheme.http, Uri.Authority(None, host, None))
  test("default") {
    assert(IgnoredHosts.default.ignored(make(Uri.RegName("localhost"))))
    assert(IgnoredHosts.default.ignored(make(Uri.Ipv4Address(ipv4"127.0.0.1"))))
    assert(IgnoredHosts.default.ignored(make(Uri.Ipv4Address(ipv4"127.1.0.1"))))
    assert(IgnoredHosts.default.ignored(make(Uri.Ipv6Address(ipv6"::1"))))
    assert(!IgnoredHosts.default.ignored(make(Uri.RegName("www.vg.no"))))
    assert(!IgnoredHosts.default.ignored(make(Uri.RegName("www.google.com"))))
    assert(!IgnoredHosts.default.ignored(make(Uri.RegName("localhost.localdomain"))))
  }
}
