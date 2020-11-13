package org.http4s.netty

import cats.implicits._
import cats.Applicative
import cats.effect.{ConcurrentEffect, ExitCase, Sync}
import io.netty.channel.{
  ChannelDuplexHandler,
  ChannelFuture,
  ChannelFutureListener,
  ChannelHandlerContext,
  ChannelPromise
}
import io.netty.handler.codec.http.{
  DefaultHttpContent,
  FullHttpMessage,
  HttpContent,
  HttpMessage,
  LastHttpContent
}
import fs2.{Chunk, Stream}
import io.netty.buffer.Unpooled
import io.netty.util.ReferenceCountUtil

import scala.collection.mutable
import scala.reflect.ClassTag

abstract class HttpStreamsHandler[
    F[_]: ConcurrentEffect,
    In <: HttpMessage: ClassTag,
    Out <: HttpMessage: ClassTag]
    extends ChannelDuplexHandler {
  protected case class Outgoing(message: Out, promise: ChannelPromise)

  private val outgoing = new mutable.Queue[Outgoing]()

  /** The incoming message that is currently being streamed out to a subscriber.
    *
    * This is tracked so that if its subscriber cancels, we can go into a mode where we ignore the rest of the body.
    * Since subscribers may cancel as many times as they like, including well after they've received all their content,
    * we need to track what the current message that's being streamed out is so that we can ignore it if it's not
    * currently being streamed out.
    */
  protected var currentlyStreamedMessage: Option[In] = None

  /** Ignore the remaining reads for the incoming message.
    *
    * This is used in conjunction with currentlyStreamedMessage, as well as in situations where we have received the
    * full body, but still might be expecting a last http content message.
    */
  private var ignoreBodyRead = false

  /** Whether a LastHttpContent message needs to be written once the incoming publisher completes.
    *
    * Since the publisher may itself publish a LastHttpContent message, we need to track this fact, because if it
    * doesn't, then we need to write one ourselves.
    */
  private var sendLastHttpContent = false

  /** Whether the given incoming message has a body.
    */
  protected def hasBody(in: In): Boolean

  /** Create a streamed incoming message with the given stream.
    */
  protected def createStreamedMessage(
      in: In,
      stream: Stream[F, Byte]): In with StreamedNettyHttpMessage[F]

  /** Invoked when an incoming message is first received.
    *
    * Overridden by sub classes for state tracking.
    */
  protected def receivedInMessage(ctx: ChannelHandlerContext): Unit = {}

  /** Invoked when an incoming message is fully consumed.
    *
    * Overridden by sub classes for state tracking.
    */
  protected def consumedInMessage(ctx: ChannelHandlerContext): Unit = {}

  /** Invoked when an outgoing message is first received.
    *
    * Overridden by sub classes for state tracking.
    */
  protected def receivedOutMessage(ctx: ChannelHandlerContext): Unit = {}

  /** Invoked when an outgoing message is fully sent.
    *
    * Overridden by sub classes for state tracking.
    */
  protected def sentOutMessage(ctx: ChannelHandlerContext): Unit = {}

  /** Subscribe the given subscriber to the given streamed message.
    *
    * Provided so that the client subclass can intercept this to hold off sending the body of an expect 100 continue
    * request.
    */
  /*protected def subscribeSubscriberToStream(msg: StreamedHttpMessage, subscriber: Subscriber[HttpContent]): Unit = {
    msg.subscribe(subscriber)
  }*/

  /** Invoked every time a read of the incoming body is requested by the subscriber.
    *
    * Provided so that the server subclass can intercept this to send a 100 continue response.
    */
  protected def onPull(ctx: ChannelHandlerContext): F[Unit] = Applicative[F].unit

  override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
    println("read stream")

    msg match {
      case msg: In with FullHttpMessage =>
        println("Full")
        receivedInMessage(ctx)
        ctx.fireChannelRead(
          createStreamedMessage(
            msg,
            Stream.evalUnChunk(HttpContentStream.chunk(msg))
          )
        )
        consumedInMessage(ctx)
      case msg: In if !hasBody(msg) =>
        receivedInMessage(ctx)
        ctx.fireChannelRead(createStreamedMessage(msg, Stream.empty))
        ignoreBodyRead = true
      case msg: In =>
        println("msg class " + msg.getClass)
        receivedInMessage(ctx)
        currentlyStreamedMessage = Some(msg)
        //val streamer = HttpContentStream.stream[F](ctx, "http4s")
        println("WAAAAT")
        val stream = Stream.chunk(Chunk.array("test".getBytes)).evalTap(_ => onPull(ctx)).onFinalizeCaseWeak {
          case ExitCase.Canceled => handleCancelled(ctx, msg)
          case _ => Applicative[F].unit
        }
        discard(ctx.fireChannelRead(createStreamedMessage(msg, stream)))

      case msg: HttpContent =>
        println("stream content")
        handleReadHttpContent(ctx, msg)
    }
  }

  override def channelReadComplete(ctx: ChannelHandlerContext): Unit = {
    if (ignoreBodyRead) ctx.read() else ctx.fireChannelReadComplete
    ()
  }

  private def handleCancelled(ctx: ChannelHandlerContext, msg: In): F[Unit] = ctx.delay {
    if (currentlyStreamedMessage.exists(_ eq msg)) {
      ignoreBodyRead = true
      // Need to do a read in case the subscriber ignored a read completed.
      ctx.read()
    }
    ()
  }

  override def write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise): Unit = {
    msg match {
      case outMsg: Out =>
        val out = Outgoing(outMsg, promise)
        receivedOutMessage(ctx)
        if (outgoing.isEmpty) {
          outgoing.enqueue(out)
          flushNext(ctx)
        } else outgoing.enqueue(out)
      case last: LastHttpContent =>
        sendLastHttpContent = false
        ctx.write(last, promise)
      case _ =>
        ctx.write(msg, promise)
    }
    ()
  }

  private def handleReadHttpContent(ctx: ChannelHandlerContext, content: HttpContent): Unit = {
    if (!ignoreBodyRead)
      content match {
        case last: LastHttpContent =>
          if (content.content.readableBytes > 0 || !last.trailingHeaders.isEmpty) { // It has data or trailing headers, send them
            ctx.fireChannelRead(content)
          } else ReferenceCountUtil.release(content)
          HttpContentStream.unregister(ctx, "http4s")
          currentlyStreamedMessage = None
          consumedInMessage(ctx)
        case _ => ctx.fireChannelRead(content)
      }
    else {
      ReferenceCountUtil.release(content)
      if (content.isInstanceOf[LastHttpContent]) {
        ignoreBodyRead = false
        currentlyStreamedMessage.foreach(_ => HttpContentStream.unregister(ctx, "http4s"))
        currentlyStreamedMessage = None
      }
    }
    ()
  }

  protected def unbufferedWrite(ctx: ChannelHandlerContext, out: Outgoing): Unit =
    out.message match {
      case _: FullHttpMessage => // Forward as is
        ctx.writeAndFlush(out.message, out.promise)
        discard(out.promise.addListener(new ChannelFutureListener() {
          override def operationComplete(channelFuture: ChannelFuture): Unit =
            ctx.unsafeRunSyncDelayed {
              sentOutMessage(ctx)
              outgoing.dequeue()
              flushNext(ctx)
            }
        }))
      case streamed: StreamedNettyHttpMessage[F] =>
        sendLastHttpContent = true
        // DON'T pass the promise through, create a new promise instead.
        ctx.writeAndFlush(out.message)
        if (ctx.channel().isWritable) {
          def write(c: Chunk[Byte]): F[Unit] =
            Sync[F].delay(ctx.writeAndFlush(chunkToNetty(c))).void

          ctx.unsafeRunSync(
            streamed.body.chunks
              .evalMap(write)
              .onFinalizeCaseWeak {
                case ExitCase.Completed => completeBody(ctx)
                case ExitCase.Error(e) =>
                  e.printStackTrace()
                  ctx.delay {
                    out.promise.tryFailure(e)
                    ctx.close()
                    ()
                  }
                case ExitCase.Canceled => Applicative[F].unit
              }
              .compile
              .drain
          )
        } else {
          ctx.unsafeRunSync(streamed.body.compile.drain)
        }
      case _ =>
    }

  def chunkToNetty(bytes: Chunk[Byte]): HttpContent =
    if (bytes.isEmpty)
      HttpContentStream.CachedEmpty
    else
      bytes match {
        case c: Chunk.Bytes =>
          new DefaultHttpContent(Unpooled.wrappedBuffer(c.values, c.offset, c.length))
        case c: Chunk.ByteBuffer =>
          new DefaultHttpContent(Unpooled.wrappedBuffer(c.buf))
        case _ =>
          new DefaultHttpContent(Unpooled.wrappedBuffer(bytes.toArray))
      }

  private def flushNext(ctx: ChannelHandlerContext): Unit = {
    if (outgoing.nonEmpty) unbufferedWrite(ctx, outgoing.front)
    else ctx.fireChannelWritabilityChanged
    ()
  }

  private def completeBody(ctx: ChannelHandlerContext): F[Unit] =
    ctx.delay {
      if (sendLastHttpContent) {
        val promise = outgoing.front.promise
        ctx
          .writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT, promise)
          .addListener(new ChannelFutureListener() {
            override def operationComplete(channelFuture: ChannelFuture): Unit =
              ctx.unsafeRunSyncDelayed {
                outgoing.dequeue()
                sentOutMessage(ctx)
                flushNext(ctx)
              }
          })
      } else {
        outgoing.dequeue().promise.setSuccess()
        sentOutMessage(ctx)
        flushNext(ctx)
      }
      ()
    }
}
