package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.Inside
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

final class EveryWorkerSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with Inside with JsonOutputFixture {

  private final case class RunInWorker(forkGroup: Option[Int], number: Int, workerIndex: Int)

  private def withEveryWorkerBuild[A](test: EveryWorkerBuild => A): A =
    withFixtureBuild(FixtureName.everyWorker)(build => test(new EveryWorkerBuild(build)))

  private def runs(json: JsonNode): Seq[JsonNode] = json.node(NodeKind.Module, "core.test").contents

  private def runsOfForkGroup(json: JsonNode, forkGroup: Int): Seq[JsonNode] =
    runs(json).filter(_.forkGroup.contains(forkGroup))

  private val suites: Seq[String] = Seq("A1", "A2", "B1", "B2")

  private def infoMessages(node: JsonNode): Seq[String] =
    node.nodes.flatMap(_.output).filter(_.kind == OutputItemKind.Info).map(_.message)

  private def givenTheModuleHoldsTwoForkGroups(): Unit =
    Given("a module whose first fork group holds suites B1 and B2, and whose second holds A1 and A2")

  private def shouldRunEachForkGroupInExactlyOneWorker(json: JsonNode): Unit = {
    Then("B1 and B2 each ran once, in the first fork group's run, and A1 and A2 each ran once, in the second's")
    runsOfForkGroup(json, 0).flatMap(_.contentNames) should contain theSameElementsAs Seq("B1", "B2")
    runsOfForkGroup(json, 1).flatMap(_.contentNames) should contain theSameElementsAs Seq("A1", "A2")

    And("each fork group has exactly one run")
    runs(json).map(_.forkGroup) shouldBe Seq(Some(0), Some(1))
  }

  private def shouldRunEachForkGroupInItsOwnWorkers(json: JsonNode): Unit = {
    Then("B1 and B2 ran only in the first fork group's runs, and A1 and A2 only in the second's")
    runsOfForkGroup(json, 0).flatMap(_.contentNames).distinct should contain theSameElementsAs Seq("B1", "B2")
    runsOfForkGroup(json, 1).flatMap(_.contentNames).distinct should contain theSameElementsAs Seq("A1", "A2")

    And("each fork group has one or two runs")
    runs(json).map(_.forkGroup).toSet shouldBe Set(Some(0), Some(1))
    Seq(0, 1).foreach(forkGroup => runsOfForkGroup(json, forkGroup).size should (be >= 1 and be <= 2))
  }

  Feature("Every worker of a JVM module") {

    Scenario("Without test parallelism, each fork group runs its suites in one worker of its own") {
      withEveryWorkerBuild { build =>
        givenTheModuleHoldsTwoForkGroups()
        And("test parallelism is off")
        When("the test command runs the module")
        val (result, json) = build.resultAndReportOfTestCommand()

        withClue(result.debugString) {
          shouldRunEachForkGroupInExactlyOneWorker(json)
        }

        And("the test command succeeds")
        shouldSucceed(result)
      }
    }

    Scenario("Under test parallelism, each fork group runs its suites in one or two workers of its own") {
      withEveryWorkerBuild { build =>
        givenTheModuleHoldsTwoForkGroups()
        And("test parallelism is on, and test sandboxing is on by default")
        build.turnOnTestParallelism()
        When("the test command runs the module with two jobs")
        val (result, json) = build.resultAndReportOfTestCommandWithJobs(2)

        withClue(result.debugString) {
          shouldRunEachForkGroupInItsOwnWorkers(json)
        }

        And("the test command succeeds")
        shouldSucceed(result)
      }
    }

    Scenario("Under test parallelism with test sandboxing off, each fork group runs its suites in one or two workers of its own") {
      withEveryWorkerBuild { build =>
        givenTheModuleHoldsTwoForkGroups()
        And("test parallelism is on and test sandboxing is off")
        build.turnOnTestParallelism()
        build.turnOffTestSandboxing()
        When("the test command runs the module with two jobs")
        val (result, json) = build.resultAndReportOfTestCommandWithJobs(2)

        withClue(result.debugString) {
          shouldRunEachForkGroupInItsOwnWorkers(json)
        }

        And("the test command succeeds")
        shouldSucceed(result)
      }
    }

    Scenario("Under testBatchFrameworkTasks, each fork group runs its suites in one worker of its own") {
      withEveryWorkerBuild { build =>
        givenTheModuleHoldsTwoForkGroups()
        And("testBatchFrameworkTasks is on and test parallelism is off")
        build.turnOnBatchFrameworkTasks()
        When("the test command runs the module with two jobs")
        val (result, json) = build.resultAndReportOfTestCommandWithJobs(2)

        withClue(result.debugString) {
          shouldRunEachForkGroupInExactlyOneWorker(json)
        }

        And("the test command succeeds")
        shouldSucceed(result)
      }
    }

    Scenario("Under testBatchFrameworkTasks, test parallelism still gives each fork group one worker") {
      withEveryWorkerBuild { build =>
        givenTheModuleHoldsTwoForkGroups()
        And("testBatchFrameworkTasks and test parallelism are both on")
        build.turnOnTestParallelism()
        build.turnOnBatchFrameworkTasks()
        When("the test command runs the module with two jobs")
        val (result, json) = build.resultAndReportOfTestCommandWithJobs(2)

        withClue(result.debugString) {
          shouldRunEachForkGroupInExactlyOneWorker(json)
        }

        And("the test command succeeds")
        shouldSucceed(result)
      }
    }

    Scenario("A test failing in the second fork group fails the test command, and every other suite still runs") {
      withEveryWorkerBuild { build =>
        givenTheModuleHoldsTwoForkGroups()
        And("test parallelism is on")
        build.turnOnTestParallelism()
        And("A2's test fails")
        build.writeSuite("A2", """fail("A2 fails")""")
        When("the test command runs the module with two jobs")
        val (result, json) = build.resultAndReportOfTestCommandWithJobs(2)

        Then("the JSON output records A2's test as failed")
        val a2Tests = runs(json).flatMap(_.contents).filter(_.name == "A2").flatMap(_.contents)
        withClue(result.debugString) {
          a2Tests.map(test => (test.name, test.outcome)) should contain(("t", Some("failed")))
        }

        And("A1, B1 and B2 each ran in one of the module's runs")
        runs(json).flatMap(_.contentNames) should contain allOf ("A1", "B1", "B2")

        And("the test command fails")
        shouldFail(result)
      }
    }

    Scenario("Under test parallelism, a fork group's runs are numbered in the order of their workers, however the queue splits the group") {
      withEveryWorkerBuild { build =>
        givenTheModuleHoldsTwoForkGroups()
        And("test parallelism is on")
        build.turnOnTestParallelism()
        And("each suite's test sends its working directory as an info message")
        suites.foreach(suite => build.writeSuite(suite, build.sendWorkingDirectory))
        When("the test command runs the module with four jobs")
        val (result, json) = build.resultAndReportOfTestCommandWithJobs(4)

        Then("each run's info messages all name the same worker folder")
        val runsInWorkers = withClue(result.debugString) {
          runs(json).flatMap { run =>
            withClue(s"in the info messages ${infoMessages(run)}: ") {
              inside(infoMessages(run).map(build.workerIndexOf).distinct) {
                case Seq() => None
                case Seq(Some(workerIndex)) => Some(RunInWorker(run.forkGroup, run.number, workerIndex))
              }
            }
          }
        }
        val workerIndexesInNumberOrderByForkGroup = Seq(0, 1).map { forkGroup =>
          runsInWorkers.filter(_.forkGroup.contains(forkGroup)).sortBy(_.number).map(_.workerIndex)
        }

        And("no two runs of a fork group ran in the same worker")
        workerIndexesInNumberOrderByForkGroup.foreach(indexes => indexes.distinct shouldBe indexes)

        And("each fork group has a run in its first worker, worker-0")
        workerIndexesInNumberOrderByForkGroup.foreach(indexes => indexes should contain(0))

        And("within each fork group, the run with the lower number ran in the worker with the lower index")
        workerIndexesInNumberOrderByForkGroup.foreach(indexes => indexes shouldBe indexes.sorted)
      }
    }

    Scenario("A fork group whose two classes must run at the same time runs in its first two workers, numbered in that order") {
      withEveryWorkerBuild { build =>
        givenTheModuleHoldsTwoForkGroups()
        And("test parallelism is on")
        build.turnOnTestParallelism()
        And("A1's and A2's tests each send their working directory as an info message, then wait up to 30 seconds for the other to start")
        val markers = build.emptyMarkersFolder()
        build.writeSuiteThatWaitsFor("A1", "A2", markers)
        build.writeSuiteThatWaitsFor("A2", "A1", markers)
        When("the test command runs the module with four jobs")
        val (result, json) = build.resultAndReportOfTestCommandWithJobs(4)

        Then("the second fork group has exactly two runs")
        val secondForkGroupRuns = runsOfForkGroup(json, 1)
        withClue(result.debugString) {
          secondForkGroupRuns should have size 2
        }

        And("one of those runs names only worker-0 in its info messages, and the other only worker-1")
        val workerIndexesInNumberOrder = secondForkGroupRuns.sortBy(_.number).map(run => infoMessages(run).map(build.workerIndexOf).distinct)
        withClue(result.debugString) {
          workerIndexesInNumberOrder should contain theSameElementsAs Seq(Seq(Some(0)), Seq(Some(1)))
        }

        And("the run in worker-0 has the lower number")
        workerIndexesInNumberOrder shouldBe Seq(Seq(Some(0)), Seq(Some(1)))
      }
    }

    Scenario("Runs are numbered by fork group even when the first fork group's run ends last") {
      withEveryWorkerBuild { build =>
        givenTheModuleHoldsTwoForkGroups()
        And("testBatchFrameworkTasks is on and test parallelism is off, so the two fork groups' workers run at the same time")
        build.turnOnBatchFrameworkTasks()
        And("B1's test sleeps before it succeeds, so the first fork group's run ends last")
        build.writeSuite("B1", "Thread.sleep(3000)")
        When("the test command runs the module with two jobs")
        val (result, json) = build.resultAndReportOfTestCommandWithJobs(2)

        Then("the first fork group's run ended after the second's")
        val endedAtByForkGroup = Seq(0, 1).map(forkGroup => runsOfForkGroup(json, forkGroup).map(_.endedAt))
        withClue(result.debugString) {
          endedAtByForkGroup.map(_.size) shouldBe Seq(1, 1)
          endedAtByForkGroup(0).head should be > endedAtByForkGroup(1).head
        }

        And("the first fork group's run is run 1 and the second's is run 2, in that order")
        runs(json).map(run => (run.number, run.forkGroup)) shouldBe Seq((1, Some(0)), (2, Some(1)))

        And("run 1 holds B1 and B2, and run 2 holds A1 and A2")
        runs(json).map(_.contentNames) shouldBe Seq(Seq("B1", "B2"), Seq("A1", "A2"))
      }
    }
  }

  Feature("Each worker of a JVM module has its own event log, output and time") {

    Scenario("Without test parallelism, each fork group's worker writes its event log in the group's processFolder") {
      withEveryWorkerBuild { build =>
        givenTheModuleHoldsTwoForkGroups()
        And("test parallelism is off")
        When("the test command runs the module")
        val (result, _) = build.resultAndReportOfTestCommand()

        Then("the only event logs are one in each fork group's processFolder")
        withClue(result.debugString) {
          build.eventLogFolders.map(_.last) should contain theSameElementsAs Seq("group-0-samples.B1", "group-1-samples.A1")
        }

        And("Mill started a worker in each of those processFolders")
        build.eventLogFolders.filterNot(folder => os.exists(folder / "testargs")) shouldBe empty
      }
    }

    Scenario("Under test parallelism, with test sandboxing off, each worker writes its event log in its own processFolder") {
      withEveryWorkerBuild { build =>
        givenTheModuleHoldsTwoForkGroups()
        And("test parallelism is on and test sandboxing is off")
        build.turnOnTestParallelism()
        build.turnOffTestSandboxing()
        When("the test command runs the module with four jobs")
        val (result, json) = build.resultAndReportOfTestCommandWithJobs(4)
        val workerFoldersByGroupFolder = build.eventLogFolders.groupMap(folder => (folder / os.up).last)(_.last)

        Then("every event log is in a worker-<i> processFolder of one of the two fork groups' folders")
        withClue(result.debugString) {
          workerFoldersByGroupFolder.keySet shouldBe Set("group-0-samples.B1", "group-1-samples.A1")
          all(workerFoldersByGroupFolder.values.flatten) should fullyMatch regex "worker-[0-9]+"
        }

        And("each fork group's first worker, worker-0, wrote an event log")
        workerFoldersByGroupFolder.values.foreach(workerFolders => workerFolders should contain("worker-0"))

        And("each of the module's runs has its own event log")
        build.eventLogFolders should have size runs(json).size

        And("Mill started a worker in each of those processFolders")
        build.eventLogFolders.filterNot(folder => os.exists(folder / "testargs")) shouldBe empty
      }
    }

    Scenario("Under testBatchFrameworkTasks, each fork group's one worker writes its event log in the group's processFolder, and no queue is laid out") {
      withEveryWorkerBuild { build =>
        givenTheModuleHoldsTwoForkGroups()
        And("testBatchFrameworkTasks and test parallelism are both on")
        build.turnOnTestParallelism()
        build.turnOnBatchFrameworkTasks()
        When("the test command runs the module with four jobs")
        val (result, _) = build.resultAndReportOfTestCommandWithJobs(4)

        Then("the only event logs are one in each fork group's processFolder")
        withClue(result.debugString) {
          build.eventLogFolders.map(_.last) should contain theSameElementsAs Seq("group-0-samples.B1", "group-1-samples.A1")
        }

        And("Mill started a worker in each of those processFolders")
        build.eventLogFolders.filterNot(folder => os.exists(folder / "testargs")) shouldBe empty

        And("neither processFolder holds a test queue or a claim folder")
        build.eventLogFolders.flatMap(folder => Seq(folder / "test-classes", folder / "claim")).filter(os.exists(_)) shouldBe empty
      }
    }

    Scenario("Under testBatchFrameworkTasks, each worker's stdout is on that worker's run alone, and none reaches the console") {
      withEveryWorkerBuild { build =>
        givenTheModuleHoldsTwoForkGroups()
        And("testBatchFrameworkTasks is on and test parallelism is off")
        build.turnOnBatchFrameworkTasks()
        And("B1's test and A1's test each write a line naming their suite to stdout")
        build.writeSuite("B1", """println("from B1")""")
        build.writeSuite("A1", """println("from A1")""")
        When("the test command runs the module")
        val (result, json) = build.resultAndReportOfTestCommand()

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
        val (result, json) = build.resultAndReportOfTestCommandWithJobs(2)

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
      withEveryWorkerBuild { build =>
        givenTheModuleHoldsTwoForkGroups()
        And("test parallelism is on")
        build.turnOnTestParallelism()
        And("B1's test writes a line to stdout, and A1's test writes a line to stderr")
        build.writeSuite("B1", """println("out from B1")""")
        build.writeSuite("A1", """System.err.println("err from A1")""")
        When("the test command runs the module with four jobs")
        val (result, json) = build.resultAndReportOfTestCommandWithJobs(4)

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

    Scenario("The module is timed from before its first worker starts until its last worker has ended") {
      withEveryWorkerBuild { build =>
        givenTheModuleHoldsTwoForkGroups()
        And("test parallelism is off")
        When("the test command runs the module")
        val (_, json) = build.resultAndReportOfTestCommand()

        Then("the module starts no later than its first run, and ends no earlier than its last run")
        val module = json.node(NodeKind.Module, "core.test")
        module.startedAt should be <= runs(json).map(_.startedAt).min
        module.endedAt should be >= runs(json).map(_.endedAt).max
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

    Scenario("When a test fails and no worker writes output, no run is shown, and both runs' suites are listed directly under the module") {
      withEveryWorkerBuild { build =>
        Given("a module holding only suites Zeta and Alpha, each in a fork group of its own, Zeta's group first, with test parallelism off")
        And("Zeta's test fails, and nothing in either run writes to stdout or stderr")
        build.holdOnlySuitesEachInItsOwnForkGroup("Zeta" -> """fail("Zeta fails")""", "Alpha" -> "()")
        When("the test command runs the module with two jobs and Mill's ticker on, as at a user's console")
        val result = build.runTestCommandAsAtTheConsole()

        Then("the human-readable output shows no run")
        withClue(result.debugString) {
          result.treeLines.filter(_.trim.startsWith("Run ")) shouldBe empty
        }

        And("Alpha and then Zeta are listed directly under the module")
        withClue(result.debugString) {
          result.treeLines.head shouldBe "[core.test]"
          result.treeLines.filter(line => line.startsWith("  ") && !line.startsWith("   ")) shouldBe Seq("  Alpha:", "  Zeta:")
        }

        And("the test command fails")
        shouldFail(result)
      }
    }
  }

  Feature("A module with no test class") {

    Scenario("A module with no test class has one empty run, which the human-readable output does not show") {
      withEveryWorkerBuild { build =>
        Given("a module holding no test class")
        build.removeSuites()
        When("the test command runs the module")
        val (result, json) = build.resultAndReportOfTestCommand()

        Then("the module has one run, run 1 of the first fork group, which ran nothing and has no outcome")
        runs(json).map(run => (run.number, run.forkGroup, run.contents, run.holds("outcome"))) shouldBe Seq(
          (1, Some(0), Nil, false)
        )

        And("the human-readable output shows the module's line alone, apart from its summary line")
        result.treeLines shouldBe Seq("[core.test]")

        And("the test command succeeds")
        shouldSucceed(result)
      }
    }

    Scenario("Under testBatchFrameworkTasks, a module with no fork group has one empty run") {
      withEveryWorkerBuild { build =>
        Given("a module whose testForkGrouping gives no fork group, holding suites A1, A2, B1 and B2")
        build.setForkGrouping("Seq()")
        And("testBatchFrameworkTasks is on")
        build.turnOnBatchFrameworkTasks()
        When("the test command runs the module")
        val (result, json) = build.resultAndReportOfTestCommand()

        Then("the module has one run, run 1 of the first fork group, which ran nothing and has no outcome")
        withClue(result.debugString) {
          runs(json).map(run => (run.number, run.forkGroup, run.contents, run.holds("outcome"))) shouldBe Seq(
            (1, Some(0), Nil, false)
          )
        }

        And("the test command succeeds")
        shouldSucceed(result)
      }
    }
  }

  Feature("A worker that cannot start") {

    Scenario("A worker that cannot start fails the test command, and the other fork groups' runs are kept") {
      withEveryWorkerBuild { build =>
        Given("a module of three fork groups, holding B1 and B2, then A1 alone, then A2 alone, with test parallelism off")
        build.setForkGrouping("""Seq(Seq("samples.B1", "samples.B2"), Seq("samples.A1"), Seq("samples.A2"))""")
        And("B1's test writes a testargs file into the second fork group's processFolder, so Mill cannot start a worker there")
        build.writeSuite(
          "B1",
          """val testargs = java.nio.file.Paths.get("..", "..", "samples.A1", "testargs")
            |    java.nio.file.Files.createDirectories(testargs.getParent)
            |    java.nio.file.Files.write(testargs, Array.emptyByteArray)""".stripMargin
        )
        When("the test command runs the module")
        val (result, json) = build.resultAndReportOfTestCommand()

        Then("the JSON output's only failure names the module and the worker that could not start")
        val failures = json.failureMessages
        withClue(result.debugString) {
          failures should have size 1
          failures.head should startWith("Starting worker 0 of fork group 1 of core.test failed: ")
        }

        And("the module's runs are the first fork group's, holding B1 and B2, then the third's, holding A2")
        runs(json).map(run => (run.forkGroup, run.contentNames)) shouldBe Seq((Some(0), Seq("B1", "B2")), (Some(2), Seq("A2")))

        And("the test command fails")
        shouldFail(result)
      }
    }
  }

  Feature("Fork groups that would share a processFolder") {

    Scenario("Two fork groups holding only the same class fail the test command before any worker starts") {
      withEveryWorkerBuild { build =>
        Given("a module of two fork groups that each hold only A1, with test parallelism off")
        build.setForkGrouping("""Seq(Seq("samples.A1"), Seq("samples.A1"))""")
        When("the test command runs the module")
        val (result, json) = build.resultAndReportOfTestCommand()

        Then("the JSON output's only failure says the two fork groups share a processFolder")
        json.failureMessages shouldBe Seq(
          "Laying out the workers of core.test failed: fork groups 0 and 1 share the processFolder samples.A1"
        )

        And("the module has no run, and no startedAt, endedAt or duration")
        runs(json) shouldBe empty
        shouldHaveNoKeys(json.node(NodeKind.Module, "core.test"), "startedAt", "endedAt", "duration")

        And("the test command fails before any worker starts")
        shouldFailBeforeAnyTestStarts(build.fixtureBuild, result)
      }
    }
  }
}

private final class EveryWorkerBuild(val fixtureBuild: FixtureBuild) {

  private val defaultForkGrouping: String = """Seq(Seq("samples.B1", "samples.B2"), Seq("samples.A1", "samples.A2"))"""

  def writeSuite(suite: String, testBody: String): Unit =
    os.write.over(
      fixtureBuild.samples / s"$suite.scala",
      s"""package samples
         |
         |import org.scalatest.funsuite.AnyFunSuite
         |
         |final class $suite extends AnyFunSuite {
         |  test("t") {
         |    $testBody
         |  }
         |}
         |""".stripMargin
    )

  def removeSuites(): Unit = os.remove.all(fixtureBuild.samples)

  val sendWorkingDirectory: String = """info(System.getProperty("user.dir"))"""

  def emptyMarkersFolder(): os.Path = {
    val markers = fixtureBuild.workspace / "markers"
    os.makeDir(markers)
    markers
  }

  def writeSuiteThatWaitsFor(suite: String, otherSuite: String, markers: os.Path): Unit = {
    val markersLiteral = "\"" + markers.toString.replace('\\', '/') + "\""
    writeSuite(
      suite,
      s"""$sendWorkingDirectory
         |    val markers = java.nio.file.Paths.get($markersLiteral)
         |    java.nio.file.Files.write(markers.resolve("$suite"), Array.emptyByteArray)
         |    val deadline = System.nanoTime() + 30L * 1000 * 1000 * 1000
         |    while (!java.nio.file.Files.exists(markers.resolve("$otherSuite")) && System.nanoTime() < deadline) {
         |      Thread.sleep(50)
         |    }
         |    assert(java.nio.file.Files.exists(markers.resolve("$otherSuite")), "$otherSuite did not start within 30 seconds")""".stripMargin
    )
  }

  def holdOnlySuitesEachInItsOwnForkGroup(testBodies: (String, String)*): Unit = {
    removeSuites()
    os.makeDir.all(fixtureBuild.samples)
    testBodies.foreach { case (suite, testBody) => writeSuite(suite, testBody) }
    setForkGrouping(testBodies.map { case (suite, _) => s"""Seq("samples.$suite")""" }.mkString("Seq(", ", ", ")"))
  }

  def setForkGrouping(forkGrouping: String): Unit = fixtureBuild.replaceInBuild(defaultForkGrouping, forkGrouping)

  def turnOnTestParallelism(): Unit = fixtureBuild.replaceInBuild("def testParallelism = false", "def testParallelism = true")

  def turnOffTestSandboxing(): Unit =
    fixtureBuild.replaceInBuild("def testForkGrouping", "def testSandboxWorkingDir = false\n    def testForkGrouping")

  def turnOnBatchFrameworkTasks(): Unit =
    fixtureBuild.replaceInBuild("def testForkGrouping", "def testBatchFrameworkTasks = true\n    def testForkGrouping")

  def resultAndReportOfTestCommand(): (TestCommandResult, JsonNode) = fixtureBuild.resultAndReportOfTestCommand()

  def resultAndReportOfTestCommandWithJobs(jobs: Int): (TestCommandResult, JsonNode) =
    fixtureBuild.resultAndReportOfTestCommand(jobsArguments(jobs))

  def runTestCommandAsAtTheConsole(): TestCommandResult =
    // With the ticker on, Mill puts its prefix on each line the test command writes.
    fixtureBuild.runTestCommandWritingReport(jobsArguments(2) ++ Seq("--ticker", "true"))

  private def jobsArguments(jobs: Int): Seq[String] = Seq("-j", jobs.toString)

  def workerIndexOf(path: String): Option[Int] =
    Some(os.Path(path))
      .filter(_.startsWith(fixtureBuild.workspace))
      // The workspace can itself sit in a `worker-<i>` folder of the build running these specs.
      .flatMap(_.relativeTo(fixtureBuild.workspace).segments.find(_.matches("worker-\\d+")))
      .map(_.stripPrefix("worker-").toInt)

  def eventLogFolders: Seq[os.Path] = fixtureBuild.eventLogFiles.map(_ / os.up)
}
