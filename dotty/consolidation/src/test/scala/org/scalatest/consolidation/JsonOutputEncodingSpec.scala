package org.scalatest.consolidation

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import org.scalatest.GivenWhenThen
import org.scalatest.events.DecodedEvent
import org.scalatest.events.TestEventLogEvent
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should.Matchers

final class JsonOutputEncodingSpec extends AnyFeatureSpec with GivenWhenThen with Matchers with ConsolidatedRuns with JsonOutputs {

  private val textJsonMustEscape = "tab\t quote\" slash\\ bell\u0007 é 😀 lone\uD800 end"

  Feature("Encoding of the JSON output") {

    Scenario("Text is written verbatim, as UTF-8 JSON, with a lone surrogate escaped: a message holding characters JSON must escape") {
      Given("a run of suite EscapeSuite whose test escapes calls info with a string holding characters JSON must escape and a lone surrogate")
      val events = eventsOf(
        """
        run started
        run: RunStarting
        structure
          suite "EscapeSuite", class "samples.EscapeSuite"
            test "escapes", label "- escapes"
        suite "EscapeSuite": SuiteStarting
        test "escapes": TestStarting
        test "escapes": InfoProvided(message: "replaced")
        test "escapes": TestSucceeded
        suite "EscapeSuite": SuiteCompleted
        run: RunCompleted
        run ended completed
        """
      ).map {
        case TestEventLogEvent.ScalaTestEvent(execution, info: DecodedEvent.InfoProvided) =>
          TestEventLogEvent.ScalaTestEvent(execution, info.copy(message = textJsonMustEscape))
        case other => other
      }

      When("the library writes the JSON output")
      val json = jsonOutputOfEvents(events)
      val utf8 = json.text.getBytes(StandardCharsets.UTF_8)

      Then("the JSON output is valid UTF-8")
      noException should be thrownBy StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(utf8))

      And("it holds the lone surrogate as a \\u escape")
      json.text should include("\\uD800")

      And("the message of test escapes's one output item is that string")
      json.node("test", "escapes")("output").only("message") shouldBe text(textJsonMustEscape)
    }
  }
}
