package net.hamnaberg.http4s.netty

import fs2.Chunk
import fs2.Chunk.KnownElementType
import io.netty.buffer.ByteBuf

import scala.reflect.ClassTag

final case class BytebufChunk(buf: ByteBuf, offset: Int, length: Int) extends Chunk[Byte] with KnownElementType[Byte] {
  checkBounds(buf, offset, length)

  override def elementClassTag: ClassTag[Byte] = ClassTag.Byte

  def size          = length
  def apply(i: Int) = buf.getByte(offset + i)
  def at(i: Int)    = buf.getByte(offset + i)

  override def drop(n: Int): Chunk[Byte] =
    if (n <= 0) this
    else if (n >= size) Chunk.empty
    else BytebufChunk(buf, offset + n, length - n)

  override def take(n: Int): Chunk[Byte] =
    if (n <= 0) Chunk.empty
    else if (n >= size) this
    else BytebufChunk(buf, offset, n)

  override def copyToArray[O2 >: Byte](xs: Array[O2], start: Int): Unit = {
    buf.readBytes(xs.asInstanceOf[Array[Byte]], start, start + offset)
    ()
  }

  override protected def splitAtChunk_(n: Int): (Chunk[Byte], Chunk[Byte]) =
    BytebufChunk(buf, offset, n) -> BytebufChunk(buf, offset + n, length - n)

  private def checkBounds(values: ByteBuf, offset: Int, length: Int): Unit = {
    val realLength = values.readableBytes()
    require(offset >= 0 && offset <= realLength)
    require(length >= 0 && length <= realLength)
    val end        = offset + length
    require(end >= 0 && end <= realLength)
  }
}

object BytebufChunk {
  def apply(buf: ByteBuf): BytebufChunk = {
    val read = buf.asReadOnly()
    BytebufChunk(read, 0, read.readableBytes())
  }
}
