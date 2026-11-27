package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should
import org.scalatest.mill.tags.AreaTag
import org.scalatest.mill.tags.Capture

@Capture
final class OutputOfEachWorkerSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with EveryWorkerFixture {

  private def shouldWriteOneEventLogInEachForkGroupsFolder(build: EveryWorkerBuild, result: TestCommandResult): Unit = {
    And("each fork group's processFolder holds one event log, and no other event log exists")
    withClue(result.debugString) {
      build.eventLogFolders.map(_.last) should contain theSameElementsAs Seq("group-0-samples.B1", "group-1-samples.A1")
    }
  }

  private def shouldStartAWorkerInEachEventLogFolder(build: EveryWorkerBuild): Unit = {
    And("Mill started a worker in each processFolder that holds an event log")
    build.eventLogFolders.filterNot(folder => os.exists(folder / "testargs")) shouldBe empty
  }

  Feature("Each worker of a JVM module has its own event log, output and time") {

    Scenario("Without test parallelism, the module's time spans its runs, and each fork group's worker writes its event log in the group's processFolder") {
      inSharedEveryWorkerBuild(sharedUnchangedEveryWorkerBuild) { build =>
        givenTheModuleHoldsTwoForkGroups()
        And("test parallelism is off")
        When("the test command runs the module")
        val (result, json) = build.resultAndJsonOutputOfTestCommand()

        Then("the module starts no later than its first run, and ends no earlier than its last run")
        val module = json.node(NodeKind.Module, "core.test")
        withClue(result.debugString) {
          module.startedAt should be <= runs(json).map(_.startedAt).min
          module.endedAt should be >= runs(json).map(_.endedAt).max
        }

        shouldWriteOneEventLogInEachForkGroupsFolder(build, result)
        shouldStartAWorkerInEachEventLogFolder(build)
      }
    }

    Scenario("Under test parallelism with test sandboxing off, each fork group runs its suites in one or two workers of its own, each worker writing its event log in its own processFolder", AreaTag.Workers) {
      withEveryWorkerBuild { build =>
        givenTheModuleHoldsTwoForkGroups()
        And("test parallelism is on and test sandboxing is off")
        build.turnOnTestParallelism()
        build.turnOffTestSandboxing()
        When("the test command runs the module with four jobs")
        val (result, json) = build.resultAndJsonOutputOfTestCommand(jobs = 4)
        val workerFoldersByGroupFolder = build.eventLogFolders.groupMap(folder => (folder / os.up).last)(_.last)

        withClue(result.debugString) {
          shouldRunEachForkGroupInItsOwnWorkers(json)
        }

        And("every event log is in a worker-<i> processFolder of one of the two fork groups' folders")
        withClue(result.debugString) {
          workerFoldersByGroupFolder.keySet shouldBe Set("group-0-samples.B1", "group-1-samples.A1")
          all(workerFoldersByGroupFolder.values.flatten) should fullyMatch regex "worker-[0-9]+"
        }

        And("each fork group's first worker, worker-0, wrote an event log")
        workerFoldersByGroupFolder.values.foreach(workerFolders => workerFolders should contain("worker-0"))

        And("each of the module's runs has its own event log")
        build.eventLogFolders should have size runs(json).size

        shouldStartAWorkerInEachEventLogFolder(build)

        And("the test command succeeds")
        shouldSucceed(result)
      }
    }

    Scenario("Under testBatchFrameworkTasks with test parallelism on, each fork group still runs its suites in one worker, which writes its event log in the group's processFolder, and no processFolder holds a test queue or a claim folder", AreaTag.Workers) {
      withEveryWorkerBuild { build =>
        givenTheModuleHoldsTwoForkGroups()
        And("testBatchFrameworkTasks and test parallelism are both on")
        build.turnOnTestParallelism()
        build.turnOnBatchFrameworkTasks()
        When("the test command runs the module with four jobs")
        val (result, json) = build.resultAndJsonOutputOfTestCommand(jobs = 4)

        withClue(result.debugString) {
          shouldRunEachForkGroupInExactlyOneWorker(json)
        }

        shouldWriteOneEventLogInEachForkGroupsFolder(build, result)
        shouldStartAWorkerInEachEventLogFolder(build)

        And("neither processFolder holds a test queue or a claim folder")
        build.eventLogFolders.flatMap(folder => Seq(folder / "test-classes", folder / "claim")).filter(os.exists(_)) shouldBe empty

        And("the test command succeeds")
        shouldSucceed(result)
      }
    }

    Scenario("Under testBatchFrameworkTasks, each worker's stdout is on that worker's run alone, and none reaches the console") {
      inSharedEveryWorkerBuild(sharedEveryWorkerBuildWithBatchFrameworkTasks) { build =>
        givenTheModuleHoldsTwoForkGroups()
        And("testBatchFrameworkTasks is on and test parallelism is off")
        And("B1's test and A1's test each write a line naming their suite to stdout")
        build.writeSuite("B1", """println("from B1")""")
        build.writeSuite("A1", """println("from A1")""")
        When("the test command runs the module")
        val (result, json) = build.resultAndJsonOutputOfTestCommand()

        Then("the first fork group's run holds B1's line on its stdout, and not A1's")
        withClue(result.debugString) {
          runsOfForkGroup(json, 0).flatMap(_.stdoutLines) should (contain("from B1") and not contain "from A1")
        }

        And("the second fork group's run holds A1's line on its stdout, and not B1's")
        runsOfForkGroup(json, 1).flatMap(_.stdoutLines) should (contain("from A1") and not contain "from B1")

        And("no console line outside the human-readable output holds either line")
        result.consoleLinesOutsideHumanReadableOutput.filter(line => line.contains("from B1") || line.contains("from A1")) shouldBe empty
      }
    }

    Scenario("Each worker's stdout lines are on that worker's run, in the order written") {
      withEveryWorkerBuild { build =>
        Given("a module holding only suites B1 and A1, each in a fork group of its own, B1's group first, with test parallelism off")
        And("B1's test and A1's test each write three numbered lines to stdout")
        build.holdOnlySuitesEachInItsOwnForkGroup(
          "B1" -> """(1 to 3).foreach(i => println("B " + i))""",
          "A1" -> """(1 to 3).foreach(i => println("A " + i))"""
        )
        When("the test command runs the module with two jobs")
        val (result, json) = build.resultAndJsonOutputOfTestCommand(jobs = 2)

        Then("the first fork group's run holds B1's lines on its stdout, in order, and none of A1's")
        val forkGroup0Lines = runsOfForkGroup(json, 0).flatMap(_.stdoutLines)
        withClue(result.debugString) {
          forkGroup0Lines.filter(_.startsWith("B ")) shouldBe (1 to 3).map(i => s"B $i")
          forkGroup0Lines.filter(_.startsWith("A ")) shouldBe empty
        }

        And("the second fork group's run holds A1's lines on its stdout, in order, and none of B1's")
        val forkGroup1Lines = runsOfForkGroup(json, 1).flatMap(_.stdoutLines)
        withClue(result.debugString) {
          forkGroup1Lines.filter(_.startsWith("A ")) shouldBe (1 to 3).map(i => s"A $i")
          forkGroup1Lines.filter(_.startsWith("B ")) shouldBe empty
        }
      }
    }

    Scenario("Under test parallelism, each worker's stdout and stderr are on that worker's run alone, and none reaches the console") {
      inSharedEveryWorkerBuild(sharedEveryWorkerBuildWithTestParallelism) { build =>
        givenTheModuleHoldsTwoForkGroups()
        And("test parallelism is on")
        And("B1's test writes a line to stdout, and A1's test writes a line to stderr")
        build.writeSuite("B1", """println("out from B1")""")
        build.writeSuite("A1", """System.err.println("err from A1")""")
        When("the test command runs the module with four jobs")
        val (result, json) = build.resultAndJsonOutputOfTestCommand(jobs = 4)

        Then("B1's line is on the stdout of a run of the first fork group, and of no run of the second")
        withClue(result.debugString) {
          runsOfForkGroup(json, 0).flatMap(_.stdoutLines) should contain("out from B1")
          runsOfForkGroup(json, 1).flatMap(_.stdoutLines) should not contain "out from B1"
        }

        And("every run whose stdout holds B1's line ran B1")
        runs(json).filter(_.stdoutLines.contains("out from B1")).foreach(run => run.contentNames should contain("B1"))

        And("A1's line is on the stderr of a run of the second fork group, and of no run of the first")
        runsOfForkGroup(json, 1).flatMap(_.stderrLines) should contain("err from A1")
        runsOfForkGroup(json, 0).flatMap(_.stderrLines) should not contain "err from A1"

        And("every run whose stderr holds A1's line ran A1")
        runs(json).filter(_.stderrLines.contains("err from A1")).foreach(run => run.contentNames should contain("A1"))

        And("no console line outside the human-readable output holds either line")
        result.consoleLinesOutsideHumanReadableOutput.filter(line => line.contains("out from B1") || line.contains("err from A1")) shouldBe empty
      }
    }
  }

  Feature("Runs in the human-readable output") {

    Scenario("Only the run whose worker wrote to stdout is shown, after the suites of the run whose worker did not") {
      withEveryWorkerBuild { build =>
        Given("a module holding only suites Zeta and Alpha, each in a fork group of its own, Zeta's group first, with test parallelism off")
        And("Zeta's test writes a line to stdout, and nothing else in either run writes to stdout or stderr")
        build.holdOnlySuitesEachInItsOwnForkGroup("Zeta" -> """println("hello")""", "Alpha" -> "()")
        When("the test command runs the module with two jobs and Mill's ticker on, as at a user's console")
        val result = build.runTestCommandAsAtTheConsole()

        Then("the human-readable output lists Alpha under the module, then shows Run 1 holding Zeta and its stdout line")
        withClue(result.debugString) {
          result.treeLines shouldBe Seq(
            "[core.test]",
            "  Alpha:",
            "    - t",
            "  Run 1:",
            "    Zeta:",
            "      - t",
            "    [stdout] hello"
          )
        }
      }
    }
  }
}
