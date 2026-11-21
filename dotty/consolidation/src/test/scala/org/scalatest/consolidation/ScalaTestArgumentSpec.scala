package org.scalatest.consolidation

import org.scalatest.consolidation.ScalaTestArgument.Flagged
import org.scalatest.consolidation.ScalaTestArgument.Unrecognised
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

final class ScalaTestArgumentSpec extends AnyWordSpec with should.Matchers {

  "A module's ScalaTest arguments" when {
    "split" should {
      "be grouped by flag in the order given, each flag with the values the runner reads for it" in {
        ScalaTestArgument.split(Seq("-oD", "-z", "-o", "-h", "target/html", "-Y", "report.css", "-PS4", "-W", "120", "60")) shouldBe Seq(
          Flagged("-o", Seq("-oD")),
          Flagged("-z", Seq("-z", "-o")),
          Flagged("-h", Seq("-h", "target/html", "-Y", "report.css")),
          Flagged("-P", Seq("-PS4")),
          Flagged("-W", Seq("-W", "120", "60"))
        )
      }

      "keep the runner argument with its file" in {
        ScalaTestArgument.split(Seq("--test-event-log", "-oD")) shouldBe Seq(
          Flagged("--test-event-log", Seq("--test-event-log", "-oD"))
        )
      }

      "keep each argument whose flag the runner does not recognise ungrouped, in place" in {
        ScalaTestArgument.split(Seq("-n", "Fast", "--unknown", "Fast", "-l", "Slow")) shouldBe Seq(
          Flagged("-n", Seq("-n", "Fast")),
          Unrecognised("--unknown"),
          Unrecognised("Fast"),
          Flagged("-l", Seq("-l", "Slow"))
        )
      }

      "be none where the module has none" in {
        ScalaTestArgument.split(Nil) shouldBe Nil
      }
    }

    "asked for the value joined to a flag" should {
      "give the rest of the argument after the flag" in {
        ScalaTestArgument.split(Seq("-Dcolour=blue", "-n", "Fast")).collect { case flagged: Flagged => flagged.joinedValue } shouldBe
          Seq(Some("colour=blue"), Some(""))
      }

      "give nothing where the flag holds no argument or its argument does not start with the flag" in {
        (Flagged("-n", Nil).joinedValue, Flagged("-n", Seq("Fast")).joinedValue) shouldBe ((None, None))
      }
    }

    "asked for a flag's values" should {
      "give the values that follow the flag" in {
        ScalaTestArgument.split(Seq("-Dcolour=blue", "-W", "120", "60")).collect { case flagged: Flagged => flagged.values } shouldBe
          Seq(Nil, Seq("120", "60"))
      }
    }

    "asked whether a flag is short of values" should {
      "say so only of a flag holding fewer values than the runner needs with it" in {
        ScalaTestArgument.split(Seq("-n", "Fast", "-W", "60")).collect { case flagged: Flagged => flagged.isShortOfValues } shouldBe
          Seq(false, true)
      }
    }
  }
}
