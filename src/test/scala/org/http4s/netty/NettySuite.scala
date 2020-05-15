package org.http4s.netty

import java.net.URI
import java.net.http.HttpResponse.BodyHandler
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.function.BiConsumer

import cats.effect.IO
import org.http4s.Uri

abstract class NettySuite extends IOSuite {
  implicit class HttpClientOps(client: HttpClient) {
    def sendIO[A](request: HttpRequest, handler: BodyHandler[A]) =
      IO.async[HttpResponse[A]](cb => {
        val future = client.sendAsync[A](request, handler)
        future.whenComplete(new P[HttpResponse[A]](either => cb(either)))
        ()
      })
  }

  private class P[T](cb: Either[Throwable, T] => Unit) extends BiConsumer[T, Throwable] {
    override def accept(v: T, e: Throwable): Unit = {
      if (e == null) cb(Right(v))
      else cb(Left(e))
    }
  }

  implicit class ToURI(uri: Uri) {
    def toURI = URI.create(uri.renderString)
  }
}
