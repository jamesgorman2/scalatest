package org.scalatest.mill

import java.nio.charset.Charset

/**
 * A line ends at a `\n` byte, and keeps it and any `\r` before it; a stream's unterminated last line has no line end.
 * Bytes are split before they are decoded, so a chunk that ends inside a character never cuts it. Malformed bytes
 * decode to U+FFFD.
 */
private[mill] final class StreamLines(charset: Charset, line: String => Unit) {

  private val splitter = new LineSplitter

  def append(bytes: Array[Byte], length: Int): Unit =
    splitter.append(bytes, length)(lineBytes => line(new String(lineBytes, charset) + "\n"))

  def end(): Unit = splitter.takeRest().foreach(lineBytes => line(new String(lineBytes, charset)))
}
