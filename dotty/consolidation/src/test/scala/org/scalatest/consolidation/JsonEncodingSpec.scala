package org.scalatest.consolidation

import java.io.StringWriter
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import org.scalatest.events.JsonReader
import org.scalatest.events.JsonValue
import org.scalatest.events.JsonWriter
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class JsonEncodingSpec extends AnyWordSpec with Matchers {

  private val textJsonMustEscape = "tab\t quote\" slash\\ bell\u0007 é 😀 lone\uD800 end"

  private def encoded(text: String): String = JsonWriter.write(JsonValue.Text(text))

  private def strictlyEncodedUtf8(text: String): Array[Byte] = {
    val buffer = StandardCharsets.UTF_8.newEncoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
      .encode(CharBuffer.wrap(text))
    val bytes = new Array[Byte](buffer.remaining())
    buffer.get(bytes)
    bytes
  }

  private def strictlyDecodedUtf8(bytes: Array[Byte]): String =
    StandardCharsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
      .decode(ByteBuffer.wrap(bytes))
      .toString

  "The JSON output's encoder" when {

    "writing a string" should {
      "escape a quote and a backslash" in {
        encoded("quote\" slash\\") shouldBe "\"quote\\\" slash\\\\\""
      }

      "escape control characters" in {
        encoded("tab\t line\n return\r bell\u0007 nul\u0000") shouldBe "\"tab\\t line\\n return\\r bell\\u0007 nul\\u0000\""
      }

      "write non-ASCII text, a surrogate pair included, verbatim" in {
        encoded("é 😀") shouldBe "\"é 😀\""
      }

      "write a lone surrogate as a \\u escape" in {
        encoded("lone\uD800 end") shouldBe "\"lone\\uD800 end\""
      }

      "write text that encodes to UTF-8" in {
        noException should be thrownBy strictlyEncodedUtf8(encoded(textJsonMustEscape))
      }

      "write text that decodes to the string it was given" in {
        val decoded = strictlyDecodedUtf8(strictlyEncodedUtf8(encoded(textJsonMustEscape)))
        JsonReader.read(decoded) shouldBe Right(JsonValue.Text(textJsonMustEscape))
      }
    }

    "writing to a writer" should {
      "write the same text as writing to a string" in {
        val value = JsonValue.Object(
          Vector(
            "text" -> JsonValue.Text(textJsonMustEscape),
            "integer" -> JsonValue.Integer(-42L),
            "flag" -> JsonValue.Bool(true),
            "items" -> JsonValue.Array(Vector(JsonValue.Bool(false), JsonValue.Object(Vector.empty), JsonValue.Array(Vector.empty)))
          )
        )
        val writer = new StringWriter
        JsonWriter.write(value, writer)
        writer.toString shouldBe JsonWriter.write(value)
      }
    }
  }
}
