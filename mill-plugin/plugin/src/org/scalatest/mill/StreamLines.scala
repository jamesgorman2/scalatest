package org.scalatest.mill

import java.nio.charset.Charset

/**
 * A line ends at a `\n` byte, and one `\r` directly before it is dropped; any other `\r` stays in the line. Bytes are
 * split before they are decoded, so a chunk that ends inside a character never cuts it. Malformed bytes decode to
 * U+FFFD.
 */
private[mill] final class StreamLines(charset: Charset, line: String => Unit) {

  private val splitter = new LineSplitter

  def append(bytes: Array[Byte], length: Int): Unit =
    splitter.append(bytes, length) { lineBytes =>
      val lineLength = if (lineBytes.nonEmpty && lineBytes.last == '\r') lineBytes.length - 1 else lineBytes.length
      line(new String(lineBytes, 0, lineLength, charset))
    }

  def end(): Unit = splitter.takeRest().foreach(lineBytes => line(new String(lineBytes, charset)))
}
