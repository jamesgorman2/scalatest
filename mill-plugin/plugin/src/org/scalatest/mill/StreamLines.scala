package org.scalatest.mill

import java.nio.charset.Charset

private[mill] final class StreamLines(charset: Charset, line: String => Unit) {

  private val splitter = new LineSplitter

  def append(bytes: Array[Byte], length: Int): Unit =
    // Bytes are split before they are decoded, so a chunk that ends inside a character never cuts it.
    splitter.append(bytes, length)(lineBytes => line(new String(lineBytes, charset) + "\n"))

  def end(): Unit = splitter.takeRest().foreach(lineBytes => line(new String(lineBytes, charset)))
}
