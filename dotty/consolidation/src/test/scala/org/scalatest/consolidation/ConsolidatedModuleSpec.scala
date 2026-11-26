package org.scalatest.consolidation

import org.scalatest.events.TestClassName
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

final class ConsolidatedModuleSpec extends AnyWordSpec with should.Matchers with ConsolidatedRuns {

  private val emptyEventStream = AssembledEventStream(None, None, None, None, None, Set.empty, Vector.empty)

  private def runIn(worker: Option[Worker], assignedTestClass: String): AssembledRun =
    AssembledRun(
      module,
      worker,
      emptyEventStream,
      Vector.empty,
      // AssembledRun is a case class: give runs in the same worker distinct assigned test classes so they are not equal.
      Vector(TestClassName(assignedTestClass))
    )

  private def runIn(forkGroup: Int, workerIndex: Int, assignedTestClass: String = "samples.Suite"): AssembledRun =
    runIn(Some(workerAt(forkGroup, workerIndex)), assignedTestClass)

  private def runGivenNoWorker(assignedTestClass: String = "samples.Suite"): AssembledRun = runIn(None, assignedTestClass)

  private def numbersOf(runs: Vector[AssembledRun]): Vector[(RunNumber, AssembledRun)] =
    ConsolidatedModule.of(module, MeasuredTimes.unmeasured, runs, Attachments.none).runs.map(run => (run.number, run.assembled))

  "A module's runs" when {
    "each given a fork group and a worker index" should {
      "be numbered from 1 by fork group, then by worker index, whatever order they were added in" in {
        val first = runIn(0, 0)
        val second = runIn(0, 1)
        val third = runIn(1, 0)
        numbersOf(Vector(third, second, first)) shouldBe Vector(RunNumber(1) -> first, RunNumber(2) -> second, RunNumber(3) -> third)
      }
      "be numbered by worker index as a number, 2 before 10" in {
        val second = runIn(0, 10)
        val first = runIn(0, 2)
        numbersOf(Vector(second, first)) shouldBe Vector(RunNumber(1) -> first, RunNumber(2) -> second)
      }
      "be numbered by fork group as a number, 2 before 10" in {
        val second = runIn(10, 0)
        val first = runIn(2, 0)
        numbersOf(Vector(second, first)) shouldBe Vector(RunNumber(1) -> first, RunNumber(2) -> second)
      }
      "keep the order they were added in where two share a fork group and worker index" in {
        val first = runIn(0, 0, "samples.First")
        val second = runIn(0, 0, "samples.Second")
        numbersOf(Vector(first, second)) shouldBe Vector(RunNumber(1) -> first, RunNumber(2) -> second)
      }
    }
    "given no worker" should {
      "be numbered in the order they were added" in {
        val first = runGivenNoWorker("samples.First")
        val second = runGivenNoWorker("samples.Second")
        numbersOf(Vector(first, second)) shouldBe Vector(RunNumber(1) -> first, RunNumber(2) -> second)
      }
      "come before the runs given one" in {
        val givenAWorker = runIn(0, 0)
        val givenNoWorker = runGivenNoWorker()
        numbersOf(Vector(givenAWorker, givenNoWorker)) shouldBe Vector(RunNumber(1) -> givenNoWorker, RunNumber(2) -> givenAWorker)
      }
    }
  }
}
