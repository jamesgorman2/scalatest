package org.scalatest.consolidation

import org.scalatest.events.RunOutcome
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

  private val unassembled = AssembledExecution(None, None, None, None, None, None, Vector.empty, Vector.empty)

  "A run assembly" when {
    "its event stream ends" should {
      "give the tree assembler it was made with what the stream recorded, and hold the tree it assembles" in {
        var recordedRuns = Vector.empty[RecordedRun]
        val run = new RunAssembly(
          moduleLabel,
          None,
          recorded => {
            recordedRuns :+= recorded
            recorded.structure.values.take(1).map(AssembledNode(_, unassembled)).toVector
          }
        )
        TestRunBuilder.build(TestRunPart.Events(eventLog)).events.foreach(built => run.add(built.event))
        run.endEventStream()
        recordedRuns.map(recorded => (recorded.structure.values.map(_.name).toVector, recorded.outcome)) shouldBe
          Vector((Vector("RowSuite", "t"), Some(RunOutcome.Completed)))
        run.topLevelSuites.map(suite => (suite.name, suite.execution)) shouldBe Vector(("RowSuite", unassembled))
      }
    }
  }
}
