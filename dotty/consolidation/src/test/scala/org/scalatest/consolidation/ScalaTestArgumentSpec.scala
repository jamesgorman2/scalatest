package org.scalatest.consolidation

import org.scalatest.consolidation.ScalaTestArgument.Flagged
import org.scalatest.consolidation.ScalaTestArgument.Unrecognised
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class ScalaTestArgumentSpec extends AnyWordSpec with Matchers {

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

      "hold the runner argument with its file" in {
        ScalaTestArgument.split(Seq("--test-event-log", "-oD")) shouldBe Seq(
          Flagged("--test-event-log", Seq("--test-event-log", "-oD"))
        )
      }

      "hold each argument whose flag the runner does not recognise ungrouped, in place" in {
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
  }
}
