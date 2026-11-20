package org.scalatest.events

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class NotationTextSpec extends AnyWordSpec with Matchers {

  "Quoted text" should {
    "be wrapped in double quotes" in {
      NotationText.quoted("pops") shouldBe "\"pops\""
    }
    "escape double quotes and backslashes" in {
      NotationText.quoted("a \"b\" \\ c") shouldBe "\"a \\\"b\\\" \\\\ c\""
    }
    "escape line feeds, carriage returns and tabs" in {
      NotationText.quoted("a\nb\rc\td") shouldBe "\"a\\nb\\rc\\td\""
    }
    "escape other control characters and surrogates as unicode escapes" in {
      NotationText.quoted("\u0001\ud83d") shouldBe "\"\\u0001\\ud83d\""
    }
    "be read back by the notation as the text it quotes" in {
      val text = "suite \"S\"\n\\ \t \u0002"
      EventNotation.parse(s"run: InfoProvided(message: ${NotationText.quoted(text)})") match {
        case Vector(event: NotationLine.Event) => event.fields shouldBe Vector("message" -> FieldValue.Text(text))
        case other => fail(s"the notation did not give one event: $other")
      }
    }
  }
}
