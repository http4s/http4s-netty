package org.http4s.netty

import cats.implicits._
import cats.ApplicativeError
import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.{Concurrent, ConcurrentEffect, Effect}
import fs2.Stream
import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter}

import scala.reflect.ClassTag

class StreamHandler[F[_]: Effect, A: ClassTag](fsm: StreamHandler.FSM[F, A])
    extends ChannelInboundHandlerAdapter {
  def stream = fsm.stream

  override def channelActive(ctx: ChannelHandlerContext): Unit = {
    ctx.unsafeRunSync(fsm.subscribe)
    //ctx.read()
    ()
  }

  override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = msg match {
    case a: A =>
      println("Matched " + a)
      ctx.unsafeRunSync(fsm.onMessage(a))
      //ctx.read()
    case _ =>
      println(s"StreamHandler: not matched ${msg}")
      ctx.fireChannelRead(msg)
      ctx.read()
  }

  override def channelReadComplete(ctx: ChannelHandlerContext): Unit =
    ctx.unsafeRunSync(fsm.onFinalize)

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit =
    ctx.unsafeRunSync(fsm.onError(cause))
}

object StreamHandler {
  trait FSM[F[_], A] {
    def subscribe: F[Unit]
    def onMessage(a: A): F[Unit]
    def onError(ex: Throwable): F[Unit]
    def onFinalize: F[Unit]
    def dequeue1: F[Either[Throwable, Option[A]]]
    def stream(implicit ev: ApplicativeError[F, Throwable]): Stream[F, A] =
      Stream.bracket(subscribe)(_ => onFinalize) >> Stream
        .eval(dequeue1)
        .repeat
        .rethrow
        .unNoneTerminate
  }

  def fsm[F[_], A](ctx: ChannelHandlerContext)(implicit F: Concurrent[F]): F[FSM[F, A]] = {
    type Out = Either[Throwable, Option[A]]

    sealed trait Input
    case object OnSubscribe extends Input
    case class OnNext(a: A) extends Input
    case class OnError(e: Throwable) extends Input
    case object OnFinalize extends Input
    case class OnDequeue(response: Deferred[F, Out]) extends Input

    sealed trait State
    case object Uninitialized extends State
    case object Idle extends State
    case class RequestBeforeSubscription(req: Deferred[F, Out]) extends State
    case class WaitingOnUpstream(elementRequest: Deferred[F, Out]) extends State
    case object UpstreamCompletion extends State
    case object DownstreamCancellation extends State
    case class UpstreamError(err: Throwable) extends State

    def step(input: Input): State => (State, F[Unit]) = input match {
      case OnSubscribe => {
        case RequestBeforeSubscription(req) => WaitingOnUpstream(req) -> F.delay(ctx.read()).void
        case Uninitialized => Idle -> F.unit

        case o =>
          val err = new Error(s"received subscription in invalid state [$o]")
          o -> (F.delay(ctx.close()) >> F.raiseError(err)).void
      }

      case OnNext(a) => {
        case WaitingOnUpstream(r) => Idle -> r.complete(a.some.asRight)
        case o => o -> F.raiseError(new Error(s"received record [$a] in invalid state [$o]"))
      }
      case OnError(e) => {
        case WaitingOnUpstream(r) => UpstreamError(e) -> r.complete(e.asLeft)
        case _ => UpstreamError(e) -> F.unit
      }
      case OnFinalize => {
        case WaitingOnUpstream(r) =>
          DownstreamCancellation -> r.complete(None.asRight)
        case Idle => DownstreamCancellation -> F.unit
        case o => o -> F.unit
      }
      case OnDequeue(r) => {
        case Uninitialized => RequestBeforeSubscription(r) -> F.delay(ctx.read()).void
        case Idle => WaitingOnUpstream(r) -> F.unit
        case err @ UpstreamError(e) => err -> r.complete(e.asLeft)
        case UpstreamCompletion => UpstreamCompletion -> r.complete(None.asRight)
        case o => o -> r.complete((new Error(s"received request in invalid state [$o]")).asLeft)
      }
    }

    Ref.of[F, State](Uninitialized).map { ref =>
      new FSM[F, A] {
        def nextState(in: Input): F[Unit] = ref.modify(step(in)).flatten
        override def subscribe: F[Unit] = nextState(OnSubscribe)

        override def onMessage(a: A): F[Unit] = nextState(OnNext(a))

        override def onError(ex: Throwable): F[Unit] = nextState(OnError(ex))

        override def onFinalize: F[Unit] = nextState(OnFinalize)

        override def dequeue1: F[Either[Throwable, Option[A]]] =
          Deferred[F, Out].flatMap(p => nextState(OnDequeue(p)) >> p.get)
      }
    }
  }

  def stream[F[_]: ConcurrentEffect, A: ClassTag](ctx: ChannelHandlerContext, name: String) =
    fsm[F, A](ctx).flatMap(fsm =>
      ctx
        .delay(new StreamHandler(fsm))
        .flatMap(handler =>
          ctx.delay(ctx.pipeline().addAfter(name, handlerName(name), handler)).as(fsm.stream)))

  def handlerName(currentHandler: String) =
    s"$currentHandler-data-handler"
}
