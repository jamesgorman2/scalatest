package org.scalatest.consolidation

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

final class ConsolidatedModuleSpec extends AnyWordSpec with should.Matchers with ConsolidatedRuns {

  private def runIn(forkGroup: Int, workerIndex: Int): RunAssembly =
    new RunAssembly(moduleLabel, Some(workerAt(forkGroup, workerIndex)), TreeAssembler.topLevelSuitesOf)

  private def runGivenNoForkGroup(): RunAssembly = new RunAssembly(moduleLabel, None, TreeAssembler.topLevelSuitesOf)

  private def numbersOf(runs: Vector[RunAssembly]): Vector[(RunNumber, RunAssembly)] =
    ConsolidatedModule.of(moduleLabel, MeasuredTimes.unmeasured, runs).runs.map(run => (run.number, run.assembly))

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
        val first = runIn(0, 0)
        val second = runIn(0, 0)
        numbersOf(Vector(first, second)) shouldBe Vector(RunNumber(1) -> first, RunNumber(2) -> second)
      }
    }
    "given no worker" should {
      "be numbered in the order they were added" in {
        val first = runGivenNoForkGroup()
        val second = runGivenNoForkGroup()
        numbersOf(Vector(first, second)) shouldBe Vector(RunNumber(1) -> first, RunNumber(2) -> second)
      }
      "come before the runs given one" in {
        val withForkGroup = runIn(0, 0)
        val withoutForkGroup = runGivenNoForkGroup()
        numbersOf(Vector(withForkGroup, withoutForkGroup)) shouldBe Vector(RunNumber(1) -> withoutForkGroup, RunNumber(2) -> withForkGroup)
      }
    }
  }
}
