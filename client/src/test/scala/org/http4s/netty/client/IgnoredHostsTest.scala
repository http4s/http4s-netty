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

import com.comcast.ip4s._
import munit.FunSuite
import org.http4s.Uri
import org.http4s.client.RequestKey

class IgnoredHostsTest extends FunSuite {
  def make(host: Uri.Host): RequestKey =
    RequestKey(Uri.Scheme.http, Uri.Authority(None, host, None))
  test("default") {
    assert(IgnoredHosts.default.ignored(make(Uri.RegName("localhost"))))
    assert(IgnoredHosts.default.ignored(make(Uri.Ipv4Address(ipv4"127.0.0.1"))))
    assert(IgnoredHosts.default.ignored(make(Uri.Ipv4Address(ipv4"127.1.0.1"))))
    assert(IgnoredHosts.default.ignored(make(Uri.Ipv6Address(ipv6"::1"))))
    assert(
      !IgnoredHosts.default.ignored(
        make(Uri.Ipv6Address(ipv6"2001:0db8:0000:0000:0000:ff00:0042:8329"))))
    assert(!IgnoredHosts.default.ignored(make(Uri.RegName("www.vg.no"))))
    assert(!IgnoredHosts.default.ignored(make(Uri.RegName("www.google.com"))))
    assert(!IgnoredHosts.default.ignored(make(Uri.RegName("localhost.localdomain"))))
  }
}
