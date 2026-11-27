package org.scalatest.mill

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should
import org.scalatest.mill.tags.Json

@Json
final class JsonOutputEncodingSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with JsonOutputFixture {

  private val escapedMessage: String = "tab\t quote\" slash\\ bell\u0007 é 😀 lone\uD800 end"

  private def isUtf8(bytes: Array[Byte]): Boolean =
    scala.util.Try(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes))).isSuccess

  private def holdsNull(value: ujson.Value): Boolean =
    value match {
      case ujson.Null => true
      case ujson.Obj(fields) => fields.values.exists(holdsNull)
      case ujson.Arr(items) => items.exists(holdsNull)
      case _ => false
    }

  Feature("Encoding of the JSON output") {

    Scenario("A message holding characters JSON must escape is written as valid UTF-8 and reads back unchanged") {
      Given("EscapeSuite, whose test `escapes` calls info with a tab, a quote, a backslash, a bell, é, an emoji and a lone surrogate")
      When("the test command is invoked as mill scalatest/test core.test -fJ <report>")
      withJsonOutputFixture("EscapeSuite") { build =>
        val json = build.jsonOutputOfTestCommand()
        val bytes = os.read.bytes(build.jsonOutputFile)

        Then("the file is valid UTF-8 and holds no JSON null")
        isUtf8(bytes) shouldBe true
        holdsNull(json.raw) shouldBe false

        And("the file holds the lone surrogate as an escape")
        val text = new String(bytes, StandardCharsets.UTF_8)
        (text.contains("\\uD800") || text.contains("\\ud800")) shouldBe true

        And("the message of the test's one output item, decoded, is the string the test sent")
        val output = json.node(NodeKind.Test, "escapes").output
        output should have size 1
        output.head.message shouldBe escapedMessage
      }
    }
  }
}
