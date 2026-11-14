package org.scalatest.mill

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import scala.collection.mutable

final class StreamLinesSpec extends AnyWordSpec with should.Matchers {

  private final class Splitting(charset: Charset = StandardCharsets.UTF_8) {
    val lines: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty[String]
    val splitter: StreamLines = new StreamLines(charset, line => lines += line)

    def append(bytes: Array[Byte]): Unit = splitter.append(bytes, bytes.length)

    def append(text: String): Unit = append(text.getBytes(charset))
  }

  "Stream lines" when {
    "given bytes holding whole lines" should {
      "pass on each line without its line end" in {
        val splitting = new Splitting
        splitting.append("first\nsecond\n")
        splitting.lines shouldBe Seq("first", "second")
      }

      "drop one carriage return before a line end" in {
        val splitting = new Splitting
        splitting.append("first\r\nsecond\r\r\n")
        splitting.lines shouldBe Seq("first", "second\r")
      }

      "keep a lone carriage return in the line" in {
        val splitting = new Splitting
        splitting.append("50%\r100%\n")
        splitting.lines shouldBe Seq("50%\r100%")
      }

      "pass on an empty line" in {
        val splitting = new Splitting
        splitting.append("\n\r\n")
        splitting.lines shouldBe Seq("", "")
      }
    }

    "given a line in several chunks" should {
      "pass it on once its line end arrives" in {
        val splitting = new Splitting
        splitting.append("fir")
        splitting.lines shouldBe empty
        splitting.append("st\nsec")
        splitting.lines shouldBe Seq("first")
      }

      "decode a character whose bytes the chunks split" in {
        val splitting = new Splitting
        val bytes = "café\n".getBytes(StandardCharsets.UTF_8)
        splitting.append(bytes.take(4))
        splitting.append(bytes.drop(4))
        splitting.lines shouldBe Seq("café")
      }

      "keep a carriage return and line end split across chunks as one line end" in {
        val splitting = new Splitting
        splitting.append("first\r")
        splitting.append("\n")
        splitting.lines shouldBe Seq("first")
      }
    }

    "given a buffer longer than the bytes read" should {
      "read only the bytes it is told were read" in {
        val splitting = new Splitting
        val buffer = "one\ntwo\n".getBytes(StandardCharsets.UTF_8)
        splitting.splitter.append(buffer, 4)
        splitting.lines shouldBe Seq("one")
      }
    }

    "given a charset other than UTF-8" should {
      "decode each line with it" in {
        val windows1252 = Charset.forName("windows-1252")
        val splitting = new Splitting(windows1252)
        splitting.append("naïve € line\n")
        splitting.lines shouldBe Seq("naïve € line")
      }

      "split a multi-byte charset's lines without cutting a character" in {
        val shiftJis = Charset.forName("MS932")
        val splitting = new Splitting(shiftJis)
        val bytes = "表示\n".getBytes(shiftJis)
        splitting.append(bytes.take(1))
        splitting.append(bytes.drop(1))
        splitting.lines shouldBe Seq("表示")
      }
    }

    "given malformed bytes" should {
      "decode them to the replacement character" in {
        val splitting = new Splitting
        splitting.append(Array[Byte]('a', 0xff.toByte, 'b', '\n'))
        splitting.lines shouldBe Seq("a�b")
      }
    }

    "ended" should {
      "pass on the line it still holds" in {
        val splitting = new Splitting
        splitting.append("first\nlast")
        splitting.splitter.end()
        splitting.lines shouldBe Seq("first", "last")
      }

      "pass on nothing when it holds no byte" in {
        val splitting = new Splitting
        splitting.append("first\n")
        splitting.splitter.end()
        splitting.lines shouldBe Seq("first")
      }

      "keep a carriage return ending the line it still holds" in {
        val splitting = new Splitting
        splitting.append("last\r")
        splitting.splitter.end()
        splitting.lines shouldBe Seq("last\r")
      }
    }
  }
}
