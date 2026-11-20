package org.scalatest.mill

import org.scalatest.consolidation.Consolidation
import org.scalatest.consolidation.ConsolidationState
import org.scalatest.consolidation.ForkGroup
import org.scalatest.consolidation.TestRunLabel
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import scala.collection.mutable

final class JvmTestModuleRunSpec extends AnyWordSpec with should.Matchers {

  private val label: TestRunLabel = TestRunLabel("core.test")

  private def forkGroupAt(position: Int): PlannedForkGroup =
    PlannedForkGroup(ForkGroup.at(position), position.toString, Seq(s"group-$position"), Nil, 1, Nil)

  "Laying out a module's fork groups" when {
    "laying out the first of two fork groups throws" should {
      "attach what it threw to the root, naming the first fork group, and fail the consolidation" in {
        val output = mutable.ArrayBuffer.empty[String]
        val consolidation = Consolidation.start(line => output += line, coloured = false)
        JvmTestModuleRun.layOutForkGroups(consolidation, label, Seq(forkGroupAt(0), forkGroupAt(1))) { forkGroup =>
          if (forkGroup == forkGroupAt(0)) throw new IllegalStateException("disk full") else "queue"
        }
        consolidation.end()
        output.toSeq.dropRight(1) shouldBe Seq(
          "Consolidated tests:",
          "  *** CONSOLIDATED TESTS ABORTED ***",
          "    Laying out fork group 0 of core.test failed: java.lang.IllegalStateException: disk full"
        )
        consolidation.state shouldBe ConsolidationState.EndedFailed
      }

      "keep only the second fork group, with what laying it out returned" in {
        val consolidation = Consolidation.start(_ => (), coloured = false)
        JvmTestModuleRun.layOutForkGroups(consolidation, label, Seq(forkGroupAt(0), forkGroupAt(1))) { forkGroup =>
          if (forkGroup == forkGroupAt(0)) throw new IllegalStateException("disk full") else s"queue of ${forkGroup.folder.mkString}"
        } shouldBe Seq(forkGroupAt(1) -> "queue of group-1")
      }
    }
  }
}
