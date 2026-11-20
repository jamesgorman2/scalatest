package org.scalatest.mill

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import scala.collection.mutable

final class StreamLinesSpec extends AnyWordSpec with should.Matchers {

  private final class StreamLinesUnderTest(charset: Charset = StandardCharsets.UTF_8) {
    val lines: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty[String]
    private val streamLines: StreamLines = new StreamLines(charset, line => lines += line)

    def append(buffer: Array[Byte], bytesRead: Int): Unit = streamLines.append(buffer, bytesRead)

    def append(bytes: Array[Byte]): Unit = append(bytes, bytes.length)

    def append(text: String): Unit = append(text.getBytes(charset))

    def end(): Unit = streamLines.end()
  }

  "Stream lines" when {
    "given bytes holding whole lines" should {
      "pass on each line with its line end" in {
        val streamLines = new StreamLinesUnderTest
        streamLines.append("first\nsecond\n")
        streamLines.lines shouldBe Seq("first\n", "second\n")
      }

      "keep each carriage return before a line end" in {
        val streamLines = new StreamLinesUnderTest
        streamLines.append("first\r\nsecond\r\r\n")
        streamLines.lines shouldBe Seq("first\r\n", "second\r\r\n")
      }

      "keep a lone carriage return in the line" in {
        val streamLines = new StreamLinesUnderTest
        streamLines.append("50%\r100%\n")
        streamLines.lines shouldBe Seq("50%\r100%\n")
      }

      "pass on an empty line" in {
        val streamLines = new StreamLinesUnderTest
        streamLines.append("\n\r\n")
        streamLines.lines shouldBe Seq("\n", "\r\n")
      }
    }

    "given a line in several chunks" should {
      "pass it on once its line end arrives" in {
        val streamLines = new StreamLinesUnderTest
        streamLines.append("fir")
        streamLines.lines shouldBe empty
        streamLines.append("st\nsec")
        streamLines.lines shouldBe Seq("first\n")
      }

      "decode a character whose bytes the chunks split" in {
        val streamLines = new StreamLinesUnderTest
        val bytes = "café\n".getBytes(StandardCharsets.UTF_8)
        streamLines.append(bytes.take(4))
        streamLines.append(bytes.drop(4))
        streamLines.lines shouldBe Seq("café\n")
      }

      "keep a carriage return and line end split across chunks as one line end" in {
        val streamLines = new StreamLinesUnderTest
        streamLines.append("first\r")
        streamLines.append("\n")
        streamLines.lines shouldBe Seq("first\r\n")
      }
    }

    "given a buffer longer than the bytes read" should {
      "read only the bytes it is told were read" in {
        val streamLines = new StreamLinesUnderTest
        val buffer = "one\ntwo\n".getBytes(StandardCharsets.UTF_8)
        streamLines.append(buffer, 4)
        streamLines.lines shouldBe Seq("one\n")
      }
    }

    "given a charset other than UTF-8" should {
      "decode each line with it" in {
        val windows1252 = Charset.forName("windows-1252")
        val streamLines = new StreamLinesUnderTest(windows1252)
        streamLines.append("naïve € line\n")
        streamLines.lines shouldBe Seq("naïve € line\n")
      }

      "split a multi-byte charset's lines without cutting a character" in {
        val shiftJis = Charset.forName("MS932")
        val streamLines = new StreamLinesUnderTest(shiftJis)
        val bytes = "表示\n".getBytes(shiftJis)
        streamLines.append(bytes.take(1))
        streamLines.append(bytes.drop(1))
        streamLines.lines shouldBe Seq("表示\n")
      }
    }

    "given malformed bytes" should {
      "decode them to the replacement character" in {
        val streamLines = new StreamLinesUnderTest
        streamLines.append(Array[Byte]('a', 0xff.toByte, 'b', '\n'))
        streamLines.lines shouldBe Seq("a�b\n")
      }
    }

    "ended" should {
      "pass on the line it still holds, with no line end" in {
        val streamLines = new StreamLinesUnderTest
        streamLines.append("first\nlast")
        streamLines.end()
        streamLines.lines shouldBe Seq("first\n", "last")
      }

      "pass on nothing when it holds no byte" in {
        val streamLines = new StreamLinesUnderTest
        streamLines.append("first\n")
        streamLines.end()
        streamLines.lines shouldBe Seq("first\n")
      }

      "keep a carriage return ending the line it still holds" in {
        val streamLines = new StreamLinesUnderTest
        streamLines.append("last\r")
        streamLines.end()
        streamLines.lines shouldBe Seq("last\r")
      }
    }
  }
}
