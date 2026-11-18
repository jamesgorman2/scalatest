package org.scalatest.mill

import java.nio.charset.StandardCharsets
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

final class EventLogLinesSpec extends AnyWordSpec with should.Matchers {

  private def bytes(text: String): Array[Byte] = text.getBytes(StandardCharsets.UTF_8)

  private def append(lines: EventLogLines, text: String): Vector[String] = {
    val chunk = bytes(text)
    lines.append(chunk, chunk.length)
  }

  "Event log lines" when {
    "given bytes holding whole lines" should {
      "return each line without its line end" in {
        append(new EventLogLines, "first\nsecond\n") shouldBe Vector("first", "second")
      }
    }

    "given a line in several chunks" should {
      "return it once its line end arrives" in {
        val lines = new EventLogLines
        append(lines, "fir") shouldBe empty
        append(lines, "st\nsec") shouldBe Vector("first")
        append(lines, "ond\n") shouldBe Vector("second")
      }

      "decode a character whose bytes the chunks split" in {
        val lines = new EventLogLines
        val text = bytes("café\n")
        lines.append(text.take(4), 4) shouldBe empty
        lines.append(text.drop(4), text.length - 4) shouldBe Vector("café")
      }
    }

    "given a buffer longer than the bytes read" should {
      "read only the bytes it is told were read" in {
        val buffer = bytes("one\ntwo\n")
        new EventLogLines().append(buffer, 4) shouldBe Vector("one")
      }
    }
  }
}
