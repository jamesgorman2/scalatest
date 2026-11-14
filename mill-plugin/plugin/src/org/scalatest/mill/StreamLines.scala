package org.scalatest.mill

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * Splits one stream's bytes, as they are read, into lines, and decodes each line with `charset`.
 *
 * A line ends at a `\n` byte, and one `\r` directly before it is dropped; any other `\r` stays in the line. Bytes are
 * split before they are decoded, so a chunk that ends inside a character never cuts it. Malformed bytes decode to
 * U+FFFD.
 *
 * @param line receives each line, without its line end, in the order the bytes complete them
 */
private[mill] final class StreamLines(charset: Charset, line: String => Unit) {

  private val held = new ByteArrayOutputStream()

  /** Adds the first `length` bytes of `bytes`, passing on each line they complete. */
  def append(bytes: Array[Byte], length: Int): Unit =
    for (i <- 0 until length) {
      if (bytes(i) == '\n') {
        complete(withoutCarriageReturn = true)
      } else {
        held.write(bytes(i))
      }
    }

  /** Ends the stream, passing on the line still held, if any byte of it was read. */
  def end(): Unit =
    if (held.size > 0) {
      complete(withoutCarriageReturn = false)
    }

  private def complete(withoutCarriageReturn: Boolean): Unit = {
    val bytes = held.toByteArray
    val length = if (withoutCarriageReturn && bytes.nonEmpty && bytes.last == '\r') bytes.length - 1 else bytes.length
    held.reset()
    line(new String(bytes, 0, length, charset))
  }
}
