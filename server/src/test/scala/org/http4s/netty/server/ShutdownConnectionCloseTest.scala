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
import cats.effect.Resource
import munit.CatsEffectSuite
import org.http4s.HttpRoutes
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.server.Server

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import scala.annotation.tailrec
import scala.concurrent.duration._

/** While the server is shutting down, connections must not be kept alive: an HTTP/1.1 response
  * written during shutdown carries `Connection: close` and the connection is closed after it, and
  * HTTP/2 connections receive a GOAWAY so that in-flight streams finish and the connection is
  * closed afterwards. Otherwise shutdown waits for the client to hang up or for the shutdown
  * timeout.
  */
class ShutdownConnectionCloseTest extends CatsEffectSuite {
  import ShutdownConnectionCloseTest._

  private val shutdownTimeout = 10.seconds
  // Well below shutdownTimeout: shutdown must not wait for the timeout to force-close connections.
  private val expectedShutdownBound = 5.seconds

  private def serverWithBlockingRoute: Resource[IO, (Server, IO[Unit], Blocking)] =
    serverWithBlockingRoute(shutdownTimeout)

  private def serverWithBlockingRoute(
      shutdownTimeout: FiniteDuration): Resource[IO, (Server, IO[Unit], Blocking)] =
    for {
      started <- Resource.eval(Deferred[IO, Unit])
      release <- Resource.eval(Deferred[IO, Unit])
      routes = HttpRoutes
        .of[IO] { case GET -> Root / "blocks" =>
          started.complete(()) *> release.get *> Ok("done")
        }
        .orNotFound
      allocated <- Resource.make(
        NettyServerBuilder[IO]
          .withHttpApp(routes)
          .withShutdownTimeout(shutdownTimeout)
          .withoutBanner
          .bindAny()
          .resource
          .allocated
          .flatMap { case (server, shutdown) => shutdown.memoize.map(server -> _) }
      )(_._2)
    } yield (allocated._1, allocated._2, Blocking(started, release))

  private def connect(server: Server): Resource[IO, Socket] =
    Resource.fromAutoCloseable(IO.blocking {
      val socket = new Socket("127.0.0.1", server.address.getPort)
      // Longer than the shutdown timeout, so that without the fix the read ends at the force-close.
      socket.setSoTimeout(15000)
      socket
    })

  private def openH2Stream(socket: Socket): IO[H2Connection] =
    IO.blocking {
      val in = new DataInputStream(socket.getInputStream)
      val out = new DataOutputStream(socket.getOutputStream)
      out.write(Preface)
      out.write(frame(TypeSettings, 0, 0, Array.emptyByteArray))
      out.write(frame(TypeHeaders, EndStream | EndHeaders, 1, getHeaders("/blocks")))
      out.flush()
      H2Connection(in, out)
    }

  /** Starts shutdown while the request is in flight, then lets the handler respond. */
  private def shutdownDuringRequest(shutdown: IO[Unit], blocking: Blocking): IO[FiniteDuration] =
    for {
      _ <- blocking.started.get.timeout(5.seconds)
      shuttingDown <- shutdown.timed.map(_._1).start
      // Give the server time to register that it is shutting down.
      _ <- IO.sleep(200.millis)
      _ <- blocking.release.complete(())
      took <- shuttingDown.joinWithNever
    } yield took

  test("HTTP/1.1: response during shutdown has Connection: close and the connection is closed") {
    serverWithBlockingRoute.use { case (server, shutdown, blocking) =>
      connect(server).use { socket =>
        for {
          _ <- IO.blocking {
            val out = socket.getOutputStream
            out.write(
              "GET /blocks HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII))
            out.flush()
          }
          reader <- IO.blocking(readUntilClosed(socket.getInputStream)).start
          took <- shutdownDuringRequest(shutdown, blocking)
          read <- reader.joinWithNever
        } yield {
          val (bytes, closedByServer) = read
          val response = new String(bytes, StandardCharsets.US_ASCII)
          val head = response.split("\r\n\r\n", 2).headOption.getOrElse("").toLowerCase
          assert(head.startsWith("http/1.1 200"), s"unexpected response: $response")
          assert(response.endsWith("done"), s"unexpected response: $response")
          assert(head.contains("connection: close"), s"missing Connection: close in:\n$head")
          assert(closedByServer, "server did not close the connection")
          assert(took < expectedShutdownBound, s"shutdown took $took")
        }
      }
    }
  }

  test("HTTP/2: GOAWAY is sent on shutdown, in-flight stream completes, connection is closed") {
    serverWithBlockingRoute.use { case (server, shutdown, blocking) =>
      connect(server).use { socket =>
        for {
          h2 <- openH2Stream(socket)
          reader <- IO.blocking(readFrames(h2.in, h2.out, Vector.empty)).start
          took <- shutdownDuringRequest(shutdown, blocking)
          events <- reader.joinWithNever
        } yield {
          val goAwayIdx = events.indexWhere {
            case Frame(TypeGoAway, _, _, _) => true
            case _ => false
          }
          val goAway = events.collectFirst { case Frame(TypeGoAway, _, _, payload) =>
            val lastStreamId = ByteBuffer.wrap(payload, 0, 4).getInt & 0x7fffffff
            val errorCode = ByteBuffer.wrap(payload, 4, 4).getInt
            (lastStreamId, errorCode)
          }
          val endOfStream1Idx = events.indexWhere {
            case Frame(TypeHeaders | TypeData, flags, 1, _) => (flags & EndStream) != 0
            case _ => false
          }
          val responseStatus = events.collectFirst { case Frame(TypeHeaders, _, 1, payload) =>
            payload.headOption.map(_ & 0xff)
          }.flatten
          assert(goAwayIdx >= 0, s"no GOAWAY received: $events")
          assert(endOfStream1Idx >= 0, s"in-flight stream did not complete: $events")
          assert(goAwayIdx < endOfStream1Idx, s"GOAWAY must precede end of stream: $events")
          // last-stream-id must include the in-flight stream, so it is processed
          assert(goAway.exists(_._1 >= 1), s"GOAWAY excludes in-flight stream: $goAway")
          assertEquals(goAway.map(_._2), Some(NoError))
          // HPACK static table index 8 is `:status 200`
          assertEquals(responseStatus, Some(0x88), s"unexpected response: $events")
          assertEquals(events.lastOption, Some(Closed), "server did not close the connection")
          assert(took < expectedShutdownBound, s"shutdown took $took")
        }
      }
    }
  }

  test("HTTP/2: a stream that never completes is closed at the shutdown timeout") {
    serverWithBlockingRoute(shutdownTimeout = 1.second).use { case (server, shutdown, blocking) =>
      connect(server).use { socket =>
        for {
          h2 <- openH2Stream(socket)
          reader <- IO.blocking(readFrames(h2.in, h2.out, Vector.empty)).start
          _ <- blocking.started.get.timeout(5.seconds)
          took <- shutdown.timed.map(_._1)
          events <- reader.joinWithNever
        } yield {
          assertEquals(events.lastOption, Some(Closed), "server did not close the connection")
          assert(took < expectedShutdownBound, s"shutdown took $took")
        }
      }
    }
  }
}

object ShutdownConnectionCloseTest {
  sealed trait Event
  final case class Frame(tpe: Int, flags: Int, streamId: Int, payload: Array[Byte]) extends Event {
    override def toString: String =
      s"Frame(type=$tpe, flags=$flags, stream=$streamId, payload=${payload.map(b => f"$b%02x").mkString})"
  }
  case object Closed extends Event
  case object TimedOut extends Event

  final case class H2Connection(in: DataInputStream, out: DataOutputStream)

  final case class Blocking(started: Deferred[IO, Unit], release: Deferred[IO, Unit])

  val TypeData = 0x0
  val TypeHeaders = 0x1
  val TypeSettings = 0x4
  val TypeGoAway = 0x7

  val EndStream = 0x1
  val EndHeaders = 0x4
  val Ack = 0x1

  val NoError = 0x0

  val Preface: Array[Byte] = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII)

  /** HPACK: `:method GET`, `:scheme http` (indexed), `:path` (literal value, indexed name),
    * `:authority localhost` (literal value, indexed name).
    */
  def getHeaders(path: String): Array[Byte] = {
    val pathBytes = path.getBytes(StandardCharsets.US_ASCII)
    val authority = "localhost".getBytes(StandardCharsets.US_ASCII)
    Array[Byte](0x82.toByte, 0x86.toByte, 0x04, pathBytes.length.toByte) ++ pathBytes ++
      Array[Byte](0x01, authority.length.toByte) ++ authority
  }

  def frame(tpe: Int, flags: Int, streamId: Int, payload: Array[Byte]): Array[Byte] = {
    val buf = ByteBuffer.allocate(9 + payload.length)
    buf.put((payload.length >> 16).toByte).put((payload.length >> 8).toByte)
    buf.put(payload.length.toByte).put(tpe.toByte).put(flags.toByte).putInt(streamId).put(payload)
    buf.array()
  }

  /** Reads all bytes until the peer closes the connection. Returns whether it was closed (as
    * opposed to the read timing out).
    */
  def readUntilClosed(in: InputStream): (Array[Byte], Boolean) = {
    val acc = new ByteArrayOutputStream()
    val buf = new Array[Byte](4096)
    @tailrec
    def loop(): Boolean =
      (try in.read(buf)
      catch { case _: SocketTimeoutException => -2 }) match {
        case -1 => true
        case -2 => false
        case n =>
          acc.write(buf, 0, n)
          loop()
      }
    val closed = loop()
    (acc.toByteArray, closed)
  }

  /** Reads frames (acking SETTINGS) until the connection is closed or the read times out. */
  @tailrec
  def readFrames(in: DataInputStream, out: DataOutputStream, acc: Vector[Event]): Vector[Event] = {
    val header = new Array[Byte](9)
    val outcome: Option[Event] =
      try { in.readFully(header); None }
      catch {
        case _: EOFException => Some(Closed)
        case _: SocketTimeoutException => Some(TimedOut)
      }
    outcome match {
      case Some(end) => acc :+ end
      case None =>
        val length = ((header(0) & 0xff) << 16) | ((header(1) & 0xff) << 8) | (header(2) & 0xff)
        val tpe = header(3).toInt
        val flags = header(4).toInt
        val streamId = ByteBuffer.wrap(header, 5, 4).getInt & 0x7fffffff
        val payload = new Array[Byte](length)
        in.readFully(payload)
        if (tpe == TypeSettings && (flags & Ack) == 0) {
          out.write(frame(TypeSettings, Ack, 0, Array.emptyByteArray))
          out.flush()
        }
        readFrames(in, out, acc :+ Frame(tpe, flags, streamId, payload))
    }
  }
}
