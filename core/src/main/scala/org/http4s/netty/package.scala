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

package org.http4s

import cats.effect.Async
import cats.syntax.all._
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.util.concurrent.{Future => NettyFuture}

package object netty {
  implicit class NettyChannelFutureSyntax[F[_]](private val fcf: F[ChannelFuture]) extends AnyVal {
    def liftToFWithChannel(implicit F: Async[F]): F[Channel] =
      fcf.flatMap(cf =>
        F.async { (callback: Either[Throwable, Channel] => Unit) =>
          void(cf.addListener { (f: ChannelFuture) =>
            if (f.isSuccess) callback(Right(f.channel()))
            else callback(Left(f.cause()))
          })
          F.delay(Some(F.delay(cf.cancel(false)).void))
        })
  }

  implicit class NettyFutureSyntax[F[_], A <: NettyFuture[_]](
      private val ff: F[A]
  ) extends AnyVal {
    def liftToF(implicit F: Async[F]): F[Unit] =
      ff.flatMap(f =>
        F.async { (callback: Either[Throwable, Unit] => Unit) =>
          void(f.addListener { (f: NettyFuture[_]) =>
            if (f.isSuccess) callback(Right(()))
            else callback(Left(f.cause()))
          })
          F.delay(Some(F.delay(f.cancel(false)).void))
        })
  }

  implicit class NettyFutureConversion[F[_], A](private val ff: F[NettyFuture[A]]) {
    def liftToFA(implicit F: Async[F]): F[A] = ff.flatMap { f =>
      F.async { cb =>
        f.addListener { (future: NettyFuture[A]) =>
          if (future.isSuccess) {
            cb(Right(future.getNow))
          } else {
            cb(Left(future.cause()))
          }
        }

        F.delay(Some(F.delay(f.cancel(false)).void))
      }
    }
  }

  def void[A](a: A): Unit = {
    val _ = a
    ()
  }
}
