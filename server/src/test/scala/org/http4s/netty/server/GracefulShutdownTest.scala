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

import cats.effect.Deferred
import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.HttpRoutes
import org.http4s.Request
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.netty.client.NettyClientBuilder

import scala.concurrent.duration._
import cats.effect.Outcome

class GracefulShutdownTest extends CatsEffectSuite {

  private val clientR = NettyClientBuilder[IO].withNioTransport.resource

  private val commonPath = "common"
  private val httpResponseText = "done"

  test("in-flight requests complete on shutdown") {
    for {
      handlerCompleted <- Deferred[IO, Unit]
      handlerStarted <- Deferred[IO, Unit]
      handlerCancelled <- Deferred[IO, Unit]

      route = HttpRoutes
        .of[IO] { case GET -> Root / `commonPath` =>
          (for {
            _ <- handlerStarted.complete(())
            _ <- IO.sleep(2.seconds)
            _ <- handlerCompleted.complete(())
            res <- Ok(httpResponseText)
          } yield res)
            .onCancel(handlerCancelled.complete(()).void)
        }
        .orNotFound

      server = NettyServerBuilder[IO]
        .withHttpApp(route)
        .withoutBanner
        .withShutdownTimeout(5.seconds)
        .bindAny()
        .resource

      _ <- server.allocated.flatMap { case (srv, release) =>
        release.memoize.flatMap { shutdown =>
          clientR
            .use { client =>
              val uri = srv.baseUri / commonPath
              client
                .expect[String](Request[IO](uri = uri))
                .background
                .use { responseFiber =>
                  for {
                    _ <- handlerStarted.get.timeout(5.seconds)
                    _ <- shutdown
                    outcome <- IO
                      .race(
                        handlerCancelled.get.as("cancelled"),
                        handlerCompleted.get.as("completed")
                      )
                      .timeout(10.seconds)
                    _ <- IO(
                      assertEquals(
                        outcome,
                        Right("completed"),
                        "handler should complete, not be cancelled during graceful shutdown"))
                    fiberResult <- responseFiber
                    _ <- fiberResult match {
                      case success: Outcome.Succeeded[IO, Throwable, String] =>
                        success.fa.map(res => assertEquals(res, httpResponseText))
                      case other =>
                        IO(fail(s"Expected http request to complete successfully, but got $other"))
                    }
                  } yield ()
                }
            }
            .guarantee(shutdown)
        }
      }
    } yield ()
  }

  test("in-flight requests are cancelled after shutdown timeout expires") {
    for {
      handlerCancelled <- Deferred[IO, Unit]
      handlerStarted <- Deferred[IO, Unit]

      route = HttpRoutes
        .of[IO] { case GET -> Root / `commonPath` =>
          (for {
            _ <- handlerStarted.complete(())
            _ <- IO.sleep(20.seconds)
            res <- Ok(httpResponseText)
          } yield res)
            .onCancel(handlerCancelled.complete(()).void)
        }
        .orNotFound

      server = NettyServerBuilder[IO]
        .withHttpApp(route)
        .withoutBanner
        .withShutdownTimeout(1.second)
        .bindAny()
        .resource

      _ <- server.allocated.flatMap { case (srv, release) =>
        release.memoize.flatMap { shutdown =>
          clientR
            .use { client =>
              val uri = srv.baseUri / commonPath
              client
                .expect[String](Request[IO](uri = uri))
                .background
                .use { responseFiber =>
                  for {
                    _ <- handlerStarted.get.timeout(5.seconds)
                    _ <- shutdown
                    _ <- handlerCancelled.get.timeout(10.seconds)
                    outcome <- responseFiber
                    _ = assert(outcome.isCanceled || outcome.isError, outcome)
                  } yield ()
                }
            }
            .guarantee(shutdown)
        }
      }
    } yield ()
  }

  test("shutdown completes early when in-flight requests finish before timeout") {
    for {
      handlerStarted <- Deferred[IO, Unit]

      route = HttpRoutes
        .of[IO] { case GET -> Root / `commonPath` =>
          for {
            _ <- handlerStarted.complete(())
            _ <- IO.sleep(2.second)
            res <- Ok(httpResponseText)
          } yield res
        }
        .orNotFound

      server = NettyServerBuilder[IO]
        .withHttpApp(route)
        .withoutBanner
        .withShutdownTimeout(20.seconds)
        .bindAny()
        .resource

      _ <- server.allocated.flatMap { case (srv, release) =>
        release.memoize.flatMap { shutdown =>
          clientR.allocated.flatMap { case (client, releaseClient) =>
            releaseClient.memoize.flatMap { closeClient =>
              val req = Request[IO](uri = srv.baseUri / commonPath)
              client
                .expect[String](req)
                .background
                .use { responseFiber =>
                  (for {
                    _ <- handlerStarted.get.timeout(5.seconds)
                    _ <- shutdown.timed.map(_._1).background.use { shutdownFiber =>
                      for {
                        outcome <- responseFiber
                        _ <- outcome match {
                          case success: Outcome.Succeeded[IO, Throwable, String] =>
                            success.fa.map(res => assertEquals(res, httpResponseText))
                          case other =>
                            IO(fail(s"Expected request to complete properly but got $other"))
                        }
                        _ <- closeClient
                        shutdownOutcome <- shutdownFiber
                        _ <- shutdownOutcome match {
                          case success: Outcome.Succeeded[IO, Throwable, FiniteDuration] =>
                            success.fa.map(d =>
                              assert(
                                d < 10.seconds,
                                s"Shutdown took $d, expected it to complete quickly after connections closed"
                              ))
                          case other =>
                            IO(fail(s"Shutdown failed unexpectedly: $other"))
                        }
                      } yield ()
                    }
                  } yield ())
                    .guarantee(closeClient)
                    .guarantee(shutdown)
                }
            }
          }
        }
      }
    } yield ()
  }
}
