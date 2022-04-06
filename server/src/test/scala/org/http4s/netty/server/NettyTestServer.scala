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

import cats.effect.{ExitCode, IO, IOApp}
import org.http4s.HttpRoutes
import org.http4s.dsl.io._
import org.http4s.implicits._

object NettyTestServer extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    val app = HttpRoutes
      .of[IO] { case GET -> Root / "hello" =>
        Ok("Hello World in " + Thread.currentThread().getName)
      }
      .orNotFound

    NettyServerBuilder[IO].withHttpApp(app).resource.use(_ => IO.never)
  }
}
