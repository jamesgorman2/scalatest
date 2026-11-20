package org.scalatest.consolidation

import org.scalatest.events.NodeId
import org.scalatest.events.RunOutcome
import org.scalatest.events.StructureNode
import org.scalatest.events.SuiteId
import org.scalatest.events.TestRunBuilder
import org.scalatest.events.TestRunPart
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

final class RunAssemblySpec extends AnyWordSpec with should.Matchers with ConsolidatedRuns {

  private val eventLog =
    """
    run started
    structure
      suite "RowSuite", class "samples.RowSuite"
        test "t", label "- t"
    suite "RowSuite": SuiteStarting
    test "t": TestStarting
    test "t": TestSucceeded
    suite "RowSuite": SuiteCompleted
    run ended completed
    """

  private val assembledTree = Vector(
    AssembledNode(
      StructureNode.Suite(NodeId.topLevelSuite(SuiteId("TreeSuite"), 0), None, None, "TreeSuite", SuiteId("TreeSuite"), "samples.TreeSuite"),
      AssembledExecution(None, None, None, None, None, None, Vector.empty, Vector.empty)
    )
  )

  private def endedEventStream(assembleTopLevelSuites: RecordedRun => Vector[AssembledNode]): AssembledEventStream = {
    val events = TestRunBuilder.build(TestRunPart.Events(eventLog)).events.map(_.event)
    events.foldLeft(RunAssembly(moduleLabel, None, assembleTopLevelSuites))(_.added(_)).assembledWholeEventStream
  }

  "A run assembly" when {
    "its event stream ends" should {
      "pass what the stream recorded to the tree assembler it was made with" in {
        var recordedRuns = Vector.empty[RecordedRun]
        endedEventStream { recorded =>
          recordedRuns :+= recorded
          assembledTree
        }
        recordedRuns.map(recorded => (recorded.structure.values.map(_.name).toVector, recorded.outcome)) shouldBe
          Vector((Vector("RowSuite", "t"), Some(RunOutcome.Completed)))
      }
      "hold the tree its tree assembler returns" in {
        endedEventStream(_ => assembledTree).topLevelSuites shouldBe assembledTree
      }
    }
  }
}
