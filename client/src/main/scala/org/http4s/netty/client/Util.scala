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

import org.http4s.HttpVersion

private[client] object Util {
  private[client] def runInVersion[B](version: HttpVersion, on2: => B, on1: => B) =
    version match {
      case HttpVersion.`HTTP/2` => on2
      case HttpVersion.`HTTP/1.1` | HttpVersion.`HTTP/1.0` => on1
      case _ => throw new IllegalStateException(s"Http version ${version} not supported")
    }

}
