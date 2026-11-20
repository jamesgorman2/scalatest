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

  private def shouldRunEachForkGroupInExactlyOneWorker(json: JsonNode): Unit = {
    Then("each of B1 and B2 is in the contents of a run whose forkGroup is 0, and in no run whose forkGroup is 1")
    runsOfForkGroup(json, 0).flatMap(_.contentNames) should contain theSameElementsAs Seq("B1", "B2")
    And("each of A1 and A2 is in the contents of a run whose forkGroup is 1, and in no run whose forkGroup is 0")
    runsOfForkGroup(json, 1).flatMap(_.contentNames) should contain theSameElementsAs Seq("A1", "A2")

    And("for each forkGroup, the module has exactly 1 run with that forkGroup")
    runs(json).map(_.forkGroup) shouldBe Seq(Some(0), Some(1))
  }

  private def shouldRunEachForkGroupInItsOwnWorkers(json: JsonNode): Unit = {
    Then("each of B1 and B2 is in the contents of at least one of the module's runs whose forkGroup is 0, and in no run whose forkGroup is 1")
    runsOfForkGroup(json, 0).flatMap(_.contentNames).distinct should contain theSameElementsAs Seq("B1", "B2")
    And("each of A1 and A2 is in the contents of at least one of the module's runs whose forkGroup is 1, and in no run whose forkGroup is 0")
    runsOfForkGroup(json, 1).flatMap(_.contentNames).distinct should contain theSameElementsAs Seq("A1", "A2")

    And("for each forkGroup, the module has either 1 or 2 runs with that forkGroup")
    runs(json).map(_.forkGroup).toSet shouldBe Set(Some(0), Some(1))
    Seq(0, 1).foreach(forkGroup => runsOfForkGroup(json, forkGroup).size should (be >= 1 and be <= 2))
  }

  Feature("Every worker of a JVM module") {

    Scenario("testParallelism false, testBatchFrameworkTasks false, testSandboxWorkingDir true") {
      withEveryWorkerBuild { build =>
        Given("the build, whose module holds suites A1, A2, B1 and B2")
        And("""the module's testForkGrouping is Seq(Seq("samples.B1", "samples.B2"), Seq("samples.A1", "samples.A2")) and its testParallelism is false""")
        When("the test command is invoked")
        val (result, json) = build.resultAndReportOfTestCommand()

        withClue(result.debugString) {
          shouldRunEachForkGroupInExactlyOneWorker(json)
        }

        And("the test command succeeds")
        shouldSucceed(result)
      }
    }

    Scenario("testParallelism true, testBatchFrameworkTasks false, testSandboxWorkingDir true") {
      withEveryWorkerBuild { build =>
        Given("the build, whose module holds suites A1, A2, B1 and B2 in fork groups (B1, B2) and (A1, A2)")
        And("the module's testParallelism is true and its testSandboxWorkingDir is true")
        build.turnOnTestParallelism()
        When("the test command is invoked")
        val (result, json) = build.resultAndReportOfTestCommandWithJobs(2)

        withClue(result.debugString) {
          shouldRunEachForkGroupInItsOwnWorkers(json)
        }

        And("the test command succeeds")
        shouldSucceed(result)
      }
    }

    Scenario("testParallelism true, testBatchFrameworkTasks false, testSandboxWorkingDir false") {
      withEveryWorkerBuild { build =>
        Given("the build, whose module holds suites A1, A2, B1 and B2 in fork groups (B1, B2) and (A1, A2)")
        And("the module's testParallelism is true and its testSandboxWorkingDir is false")
        build.turnOnTestParallelism()
        build.turnOffTestSandboxing()
        When("the test command is invoked")
        val (result, json) = build.resultAndReportOfTestCommandWithJobs(2)

        withClue(result.debugString) {
          shouldRunEachForkGroupInItsOwnWorkers(json)
        }

        And("the test command succeeds")
        shouldSucceed(result)
      }
    }

    Scenario("testParallelism false, testBatchFrameworkTasks true, testSandboxWorkingDir true") {
      withEveryWorkerBuild { build =>
        Given("the build, whose module holds suites A1, A2, B1 and B2 in fork groups (B1, B2) and (A1, A2)")
        And("the module's testParallelism is false and its testBatchFrameworkTasks is true")
        build.turnOnBatchFrameworkTasks()
        When("the test command is invoked")
        val (result, json) = build.resultAndReportOfTestCommandWithJobs(2)

        withClue(result.debugString) {
          shouldRunEachForkGroupInExactlyOneWorker(json)
        }

        And("the test command succeeds")
        shouldSucceed(result)
      }
    }

    Scenario("testParallelism true, testBatchFrameworkTasks true, testSandboxWorkingDir true") {
      withEveryWorkerBuild { build =>
        Given("the build, whose module holds suites A1, A2, B1 and B2 in fork groups (B1, B2) and (A1, A2)")
        And("the module's testParallelism is true and its testBatchFrameworkTasks is true")
        build.turnOnTestParallelism()
        build.turnOnBatchFrameworkTasks()
        When("the test command is invoked")
        val (result, json) = build.resultAndReportOfTestCommandWithJobs(2)

        withClue(result.debugString) {
          shouldRunEachForkGroupInExactlyOneWorker(json)
        }

        And("the test command succeeds")
        shouldSucceed(result)
      }
    }

    Scenario("A failing test in the second fork group") {
      withEveryWorkerBuild { build =>
        Given("the build, whose module holds suites A1, A2, B1 and B2 in fork groups (B1, B2) and (A1, A2)")
        And("the module's testParallelism is true")
        build.turnOnTestParallelism()
        And("A2's test t fails")
        build.writeSuite("A2", """fail("A2 fails")""")
        When("the test command is invoked")
        val (result, json) = build.resultAndReportOfTestCommandWithJobs(2)

        Then("the JSON output holds a test t in suite A2 whose outcome is failed")
        val a2Tests = runs(json).flatMap(_.contents).filter(_.name == "A2").flatMap(_.contents)
        withClue(result.debugString) {
          a2Tests.map(test => (test.name, test.outcome)) should contain(("t", Some("failed")))
        }

        And("each of A1, B1 and B2 is in the contents of one of the module's runs")
        runs(json).flatMap(_.contentNames) should contain allOf ("A1", "B1", "B2")

        And("the test command fails")
        shouldFail(result)
      }
    }

    Scenario("Two workers in each fork group") {
      withEveryWorkerBuild { build =>
        Given("the build, whose module holds suites A1, A2, B1 and B2 in fork groups (B1, B2) and (A1, A2)")
        And("the module's testParallelism is true")
        build.turnOnTestParallelism()
        And("each suite's test t sleeps for 3 seconds, then sends an info message holding the system property user.dir")
        suites.foreach(suite => build.writeSuite(suite, "Thread.sleep(3000); info(System.getProperty(\"user.dir\"))"))
        When("the test command is invoked as mill -j 4 scalatest/test core.test -fJ <report>")
        val (result, json) = build.resultAndReportOfTestCommandWithJobs(4)

        Then("for each forkGroup, the module has exactly two runs with that forkGroup")
        withClue(result.debugString) {
          Seq(0, 1).map(forkGroup => runsOfForkGroup(json, forkGroup).size) shouldBe Seq(2, 2)
        }

        And("within each of the module's runs, every info message is a path holding one and the same folder worker-<i>")
        val runsInWorkers = runs(json).map { run =>
          withClue(s"in the info messages ${infoMessages(run)}: ") {
            inside(infoMessages(run).map(build.workerIndexOf).distinct) { case Seq(Some(workerIndex)) =>
              RunInWorker(run.forkGroup, run.number, workerIndex)
            }
          }
        }

        And("of the two runs with each forkGroup, the one with the lower number has the lower <i>")
        Seq(0, 1).foreach { forkGroup =>
          val indexesInNumberOrder = runsInWorkers.filter(_.forkGroup.contains(forkGroup)).sortBy(_.number).map(_.workerIndex)
          indexesInNumberOrder.distinct should have size 2
          indexesInNumberOrder shouldBe indexesInNumberOrder.sorted
        }
      }
    }

    Scenario("The first fork group's run ends last") {
      withEveryWorkerBuild { build =>
        Given("the build, whose module holds suites A1, A2, B1 and B2 in fork groups (B1, B2) and (A1, A2)")
        And("the module's testParallelism is false and its testBatchFrameworkTasks is true, so its two fork groups' workers run at the same time")
        build.turnOnBatchFrameworkTasks()
        And("B1's test t sleeps for 3 seconds before it succeeds")
        build.writeSuite("B1", "Thread.sleep(3000)")
        When("the test command is invoked")
        val (result, json) = build.resultAndReportOfTestCommandWithJobs(2)

        Then("the endedAt of the run whose forkGroup is 0 is later than the endedAt of the run whose forkGroup is 1")
        val endedAtByForkGroup = Seq(0, 1).map(forkGroup => runsOfForkGroup(json, forkGroup).map(_.endedAt))
        withClue(result.debugString) {
          endedAtByForkGroup.map(_.size) shouldBe Seq(1, 1)
          endedAtByForkGroup(0).head should be > endedAtByForkGroup(1).head
        }

        And("the module's runs are, in order, a run whose number is 1 and forkGroup is 0, then a run whose number is 2 and forkGroup is 1")
        runs(json).map(run => (run.number, run.forkGroup)) shouldBe Seq((1, Some(0)), (2, Some(1)))

        And("the run whose number is 1 holds suites B1 and B2, and the run whose number is 2 holds suites A1 and A2")
        runs(json).map(_.contentNames) shouldBe Seq(Seq("B1", "B2"), Seq("A1", "A2"))
      }
    }
  }

  Feature("Each worker of a JVM module has its own event log, output and time") {

    Scenario("Each worker writes its event log in its own processFolder") {
      withEveryWorkerBuild { build =>
        Given("the build, whose module holds suites A1, A2, B1 and B2 in fork groups (B1, B2) and (A1, A2), with testParallelism false")
        When("the test command is invoked")
        val (result, _) = build.resultAndReportOfTestCommand()

        Then("an event log file is in each of the two processFolders Mill's testForked lays out for the fork groups, and nowhere else")
        withClue(result.debugString) {
          build.eventLogFolders.map(_.last) should contain theSameElementsAs Seq("group-0-samples.B1", "group-1-samples.A1")
        }

        And("Mill started a worker in each of those processFolders")
        build.eventLogFolders.filterNot(folder => os.exists(folder / "testargs")) shouldBe empty
      }
    }

    Scenario("Under test parallelism, with test sandboxing off, each worker writes its event log in its own processFolder") {
      withEveryWorkerBuild { build =>
        Given("the build, whose module holds suites A1, A2, B1 and B2 in fork groups (B1, B2) and (A1, A2)")
        And("the module's testParallelism is true and its testSandboxWorkingDir is false")
        build.turnOnTestParallelism()
        build.turnOffTestSandboxing()
        And("each suite's test t sleeps for 3 seconds, so each fork group's two workers each claim a class")
        suites.foreach(suite => build.writeSuite(suite, "Thread.sleep(3000)"))
        When("the test command is invoked as mill -j 4 scalatest/test core.test -fJ <report>")
        val (result, _) = build.resultAndReportOfTestCommandWithJobs(4)

        Then("an event log file is in each worker's own processFolder, worker-0 and worker-1 of each fork group's folder, and nowhere else")
        withClue(result.debugString) {
          build.eventLogFolders.map(folder => s"${(folder / os.up).last}/${folder.last}") should contain theSameElementsAs Seq(
            "group-0-samples.B1/worker-0",
            "group-0-samples.B1/worker-1",
            "group-1-samples.A1/worker-0",
            "group-1-samples.A1/worker-1"
          )
        }

        And("Mill started a worker in each of those processFolders")
        build.eventLogFolders.filterNot(folder => os.exists(folder / "testargs")) shouldBe empty
      }
    }

    Scenario("Under testBatchFrameworkTasks, each fork group's one worker writes its event log in the group's processFolder, and no queue is laid out") {
      withEveryWorkerBuild { build =>
        Given("the build, whose module holds suites A1, A2, B1 and B2 in fork groups (B1, B2) and (A1, A2)")
        And("the module's testParallelism is true and its testBatchFrameworkTasks is true")
        build.turnOnTestParallelism()
        build.turnOnBatchFrameworkTasks()
        When("the test command is invoked as mill -j 4 scalatest/test core.test -fJ <report>")
        val (result, _) = build.resultAndReportOfTestCommandWithJobs(4)

        Then("an event log file is in each of the two processFolders Mill's testForked lays out for the fork groups, and nowhere else")
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
        Given("the build, whose module holds suites A1, A2, B1 and B2 in fork groups (B1, B2) and (A1, A2)")
        And("the module's testParallelism is false and its testBatchFrameworkTasks is true")
        build.turnOnBatchFrameworkTasks()
        And("B1's test t writes the line from B1 to stdout, and A1's test t writes the line from A1 to stdout")
        build.writeSuite("B1", """println("from B1")""")
        build.writeSuite("A1", """println("from A1")""")
        When("the test command is invoked")
        val (result, json) = build.resultAndReportOfTestCommand()

        Then("the stdout of the run whose forkGroup is 0 holds from B1 and not from A1")
        withClue(result.debugString) {
          runsOfForkGroup(json, 0).flatMap(_.stdoutLines) should (contain("from B1") and not contain "from A1")
        }

        And("the stdout of the run whose forkGroup is 1 holds from A1 and not from B1")
        runsOfForkGroup(json, 1).flatMap(_.stdoutLines) should (contain("from A1") and not contain "from B1")

        And("no console line outside the human-readable output holds either line")
        result.consoleLinesOutsideHumanReadableOutput.filter(line => line.contains("from B1") || line.contains("from A1")) shouldBe empty
      }
    }

    Scenario("Two workers each write to stdout") {
      withEveryWorkerBuild { build =>
        Given("the build, whose module holds suites A1 and B1")
        And("""the module's testForkGrouping is Seq(Seq("samples.B1"), Seq("samples.A1")) and its testParallelism is false""")
        And("B1's test t writes the lines B 1 to B 3 to stdout, and A1's test t writes the lines A 1 to A 3 to stdout")
        build.holdOnlySuitesEachInItsOwnForkGroup(
          "B1" -> """(1 to 3).foreach(i => println("B " + i))""",
          "A1" -> """(1 to 3).foreach(i => println("A " + i))"""
        )
        When("the test command is invoked")
        val (result, json) = build.resultAndReportOfTestCommandWithJobs(2)

        Then("the stdout text of the output of the run whose forkGroup is 0 holds the lines B 1 to B 3, in order, and no line beginning A ")
        val forkGroup0Lines = runsOfForkGroup(json, 0).flatMap(_.stdoutLines)
        withClue(result.debugString) {
          forkGroup0Lines.filter(_.startsWith("B ")) shouldBe (1 to 3).map(i => s"B $i")
          forkGroup0Lines.filter(_.startsWith("A ")) shouldBe empty
        }

        And("the stdout text of the output of the run whose forkGroup is 1 holds the lines A 1 to A 3, in order, and no line beginning B ")
        val forkGroup1Lines = runsOfForkGroup(json, 1).flatMap(_.stdoutLines)
        withClue(result.debugString) {
          forkGroup1Lines.filter(_.startsWith("A ")) shouldBe (1 to 3).map(i => s"A $i")
          forkGroup1Lines.filter(_.startsWith("B ")) shouldBe empty
        }
      }
    }

    Scenario("Under test parallelism, each worker's stdout and stderr are on that worker's run alone, and none reaches the console") {
      withEveryWorkerBuild { build =>
        Given("the build, whose module holds suites A1, A2, B1 and B2 in fork groups (B1, B2) and (A1, A2)")
        And("the module's testParallelism is true")
        build.turnOnTestParallelism()
        And("each suite's test t sleeps for 3 seconds, so each fork group's two workers each claim a class")
        And("B1's test t then writes the line out from B1 to stdout, and A1's test t writes the line err from A1 to stderr")
        build.writeSuite("B1", """Thread.sleep(3000); println("out from B1")""")
        build.writeSuite("A1", """Thread.sleep(3000); System.err.println("err from A1")""")
        Seq("B2", "A2").foreach(suite => build.writeSuite(suite, "Thread.sleep(3000)"))
        When("the test command is invoked as mill -j 4 scalatest/test core.test -fJ <report>")
        val (result, json) = build.resultAndReportOfTestCommandWithJobs(4)

        Then("the stdout of a run whose forkGroup is 0 holds out from B1, and no run whose forkGroup is 1 holds it")
        withClue(result.debugString) {
          runsOfForkGroup(json, 0).flatMap(_.stdoutLines) should contain("out from B1")
          runsOfForkGroup(json, 1).flatMap(_.stdoutLines) should not contain "out from B1"
        }

        And("every run whose stdout holds out from B1 holds suite B1")
        runs(json).filter(_.stdoutLines.contains("out from B1")).foreach(run => run.contentNames should contain("B1"))

        And("the stderr of a run whose forkGroup is 1 holds err from A1, and no run whose forkGroup is 0 holds it")
        runsOfForkGroup(json, 1).flatMap(_.stderrLines) should contain("err from A1")
        runsOfForkGroup(json, 0).flatMap(_.stderrLines) should not contain "err from A1"

        And("every run whose stderr holds err from A1 holds suite A1")
        runs(json).filter(_.stderrLines.contains("err from A1")).foreach(run => run.contentNames should contain("A1"))

        And("no console line outside the human-readable output holds either line")
        result.consoleLinesOutsideHumanReadableOutput.filter(line => line.contains("out from B1") || line.contains("err from A1")) shouldBe empty
      }
    }

    Scenario("The module is timed from before its first worker starts until its last worker has ended") {
      withEveryWorkerBuild { build =>
        Given("the build, whose module holds suites A1, A2, B1 and B2 in fork groups (B1, B2) and (A1, A2), with testParallelism false")
        When("the test command is invoked")
        val (_, json) = build.resultAndReportOfTestCommand()

        Then("the module starts no later than its first run, and ends no earlier than its last run")
        val module = json.node(NodeKind.Module, "core.test")
        module.startedAt should be <= runs(json).map(_.startedAt).min
        module.endedAt should be >= runs(json).map(_.endedAt).max
      }
    }
  }

  Feature("Runs in the human-readable output") {

    Scenario("One worker writes to stdout") {
      withEveryWorkerBuild { build =>
        Given("the build, whose module holds suites Zeta and Alpha")
        And("""the module's testForkGrouping is Seq(Seq("samples.Zeta"), Seq("samples.Alpha")) and its testParallelism is false""")
        And("Zeta's test t writes the line hello to stdout")
        And("nothing else in either run writes to stdout or stderr")
        build.holdOnlySuitesEachInItsOwnForkGroup("Zeta" -> """println("hello")""", "Alpha" -> "()")
        When("the test command is invoked")
        val result = build.runTestCommandAsAtTheConsole()

        Then("the human-readable output, apart from its summary line, is:")
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

    Scenario("A failing test in one of two workers") {
      withEveryWorkerBuild { build =>
        Given("the build, whose module holds suites Zeta and Alpha")
        And("""the module's testForkGrouping is Seq(Seq("samples.Zeta"), Seq("samples.Alpha")) and its testParallelism is false""")
        And("Zeta's test t fails")
        And("nothing in either run writes to stdout or stderr")
        build.holdOnlySuitesEachInItsOwnForkGroup("Zeta" -> """fail("Zeta fails")""", "Alpha" -> "()")
        When("the test command is invoked")
        val result = build.runTestCommandAsAtTheConsole()

        Then("the human-readable output holds no line beginning Run ")
        withClue(result.debugString) {
          result.treeLines.filter(_.trim.startsWith("Run ")) shouldBe empty
        }

        And("the line `  Alpha:` comes before the line `  Zeta:`, each directly under `[core.test]`")
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

    Scenario("No test class") {
      withEveryWorkerBuild { build =>
        Given("the build, whose module holds no test class")
        build.removeSuites()
        When("the test command is invoked")
        val (result, json) = build.resultAndReportOfTestCommand()

        Then("the module's runs are exactly one run, whose number is 1 and forkGroup is 0, whose contents is [] and which has no outcome")
        runs(json).map(run => (run.number, run.forkGroup, run.contents, run.holds("outcome"))) shouldBe Seq(
          (1, Some(0), Nil, false)
        )

        And("the human-readable output, apart from its summary line, is [core.test]")
        result.treeLines shouldBe Seq("[core.test]")

        And("the test command succeeds")
        shouldSucceed(result)
      }
    }

    Scenario("Under testBatchFrameworkTasks, a module with no fork group") {
      withEveryWorkerBuild { build =>
        Given("the build, whose module holds suites A1, A2, B1 and B2, and whose testForkGrouping is Seq()")
        build.setForkGrouping("Seq()")
        And("the module's testBatchFrameworkTasks is true")
        build.turnOnBatchFrameworkTasks()
        When("the test command is invoked")
        val (result, json) = build.resultAndReportOfTestCommand()

        Then("the module's runs are exactly one run, whose number is 1 and forkGroup is 0, whose contents is [] and which has no outcome")
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

    Scenario("The second of three fork groups' worker cannot start") {
      withEveryWorkerBuild { build =>
        Given("the build, whose module holds suites A1, A2, B1 and B2")
        And("""the module's testForkGrouping is Seq(Seq("samples.B1", "samples.B2"), Seq("samples.A1"), Seq("samples.A2")) and its testParallelism is false""")
        build.setForkGrouping("""Seq(Seq("samples.B1", "samples.B2"), Seq("samples.A1"), Seq("samples.A2"))""")
        And("B1's test t writes a testargs file into the second fork group's processFolder, so Mill cannot start a worker there")
        build.writeSuite(
          "B1",
          """val testargs = java.nio.file.Paths.get("..", "..", "samples.A1", "testargs")
            |    java.nio.file.Files.createDirectories(testargs.getParent)
            |    java.nio.file.Files.write(testargs, Array.emptyByteArray)""".stripMargin
        )
        When("the test command is invoked")
        val (result, json) = build.resultAndReportOfTestCommand()

        Then("the root's failures are exactly one, naming the module and its worker that could not start")
        val failures = json.failureMessages
        withClue(result.debugString) {
          failures should have size 1
          failures.head should startWith("Starting worker 0 of fork group 1 of core.test failed: ")
        }

        And("the module's runs are exactly a run whose forkGroup is 0 and which holds suites B1 and B2, then a run whose forkGroup is 2 and which holds suite A2")
        runs(json).map(run => (run.forkGroup, run.contentNames)) shouldBe Seq((Some(0), Seq("B1", "B2")), (Some(2), Seq("A2")))

        And("the test command fails")
        shouldFail(result)
      }
    }
  }

  Feature("Fork groups that would share a processFolder") {

    Scenario("Two fork groups each hold only the same class") {
      withEveryWorkerBuild { build =>
        Given("the build, whose module holds suites A1, A2, B1 and B2")
        And("""the module's testForkGrouping is Seq(Seq("samples.A1"), Seq("samples.A1")) and its testParallelism is false""")
        build.setForkGrouping("""Seq(Seq("samples.A1"), Seq("samples.A1"))""")
        When("the test command is invoked")
        val (result, json) = build.resultAndReportOfTestCommand()

        Then("the root's failures are exactly Laying out the workers of core.test failed: fork groups 0 and 1 share the processFolder samples.A1")
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

  /** The module holds only the given suites, each in its own fork group, in the order given. */
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

  /** Mill's ticker is on, as at a user's console, so Mill puts its prefix on each line the test command writes. */
  def runTestCommandAsAtTheConsole(): TestCommandResult = fixtureBuild.runTestCommandWritingReport(jobsArguments(2) ++ Seq("--ticker", "true"))

  private def jobsArguments(jobs: Int): Seq[String] = Seq("-j", jobs.toString)

  // The build's workspace can itself sit in a folder `worker-<i>` of the build running these tests, so only the part of
  // the path below the workspace names the build's own worker.
  def workerIndexOf(path: String): Option[Int] =
    Some(os.Path(path))
      .filter(_.startsWith(fixtureBuild.workspace))
      .flatMap(_.relativeTo(fixtureBuild.workspace).segments.find(_.matches("worker-\\d+")))
      .map(_.stripPrefix("worker-").toInt)

  def eventLogFolders: Seq[os.Path] = fixtureBuild.eventLogFiles.map(_ / os.up)
}
