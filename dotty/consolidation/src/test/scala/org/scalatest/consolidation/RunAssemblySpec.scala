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

  private def endedRun(assembleTree: RecordedRun => Vector[AssembledNode]): RunAssembly = {
    val run = new RunAssembly(moduleLabel, None, assembleTree)
    TestRunBuilder.build(TestRunPart.Events(eventLog)).events.foreach(built => run.add(built.event))
    run.endEventStream()
    run
  }

  "A run assembly" when {
    "its event stream ends" should {
      "give the tree assembler it was made with what the stream recorded" in {
        var recordedRuns = Vector.empty[RecordedRun]
        endedRun { recorded =>
          recordedRuns :+= recorded
          assembledTree
        }
        recordedRuns.map(recorded => (recorded.structure.values.map(_.name).toVector, recorded.outcome)) shouldBe
          Vector((Vector("RowSuite", "t"), Some(RunOutcome.Completed)))
      }
      "hold the tree its tree assembler assembles" in {
        endedRun(_ => assembledTree).topLevelSuites shouldBe assembledTree
      }
    }
  }
}
