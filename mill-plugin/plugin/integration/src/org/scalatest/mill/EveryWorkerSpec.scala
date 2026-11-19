package org.scalatest.mill

import mill.testkit.IntegrationTester
import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should
import scala.util.matching.Regex

final class EveryWorkerSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with JsonOutputFixture {

  private def samples(tester: IntegrationTester): os.Path = tester.workspacePath / "core" / "test" / "src" / "samples"

  private def writeSuite(tester: IntegrationTester, suite: String, testBody: String): Unit =
    os.write.over(
      samples(tester) / s"$suite.scala",
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

  private def runs(json: ujson.Value): Seq[ujson.Value] = contents(node(json, "module", "core.test"))

  private def suiteNames(run: ujson.Value): Seq[String] = contents(run).map(_("name").str)

  private def runsOfForkGroup(json: ujson.Value, forkGroup: Int): Seq[ujson.Value] =
    runs(json).filter(_.obj.get("forkGroup").contains(ujson.Num(forkGroup)))

  private def stdoutLines(run: ujson.Value): Seq[String] = capturedLines(run, "stdout")

  private def stderrLines(run: ujson.Value): Seq[String] = capturedLines(run, "stderr")

  private def capturedLines(run: ujson.Value, stream: String): Seq[String] =
    run.obj.get("output").toSeq.flatMap(_.arr).filter(_("kind").str == "stdio").filter(_("stream").str == stream).map(_("text").str).mkString.linesIterator.toSeq

  private def jsonOutputOf(tester: IntegrationTester): (TestCommandResult, ujson.Value) = {
    val result = runTestCommand(tester, "core.test", "-fJ", report(tester).toString)
    (result, withClue(result.debugString)(jsonOutput(report(tester))))
  }

  private def jsonOutputWithJobs(tester: IntegrationTester, jobs: Int): (TestCommandResult, ujson.Value) = {
    val result = runTestCommandWithJobs(tester, jobs)
    (result, withClue(result.debugString)(jsonOutput(report(tester))))
  }

  private def runTestCommandWithJobs(tester: IntegrationTester, jobs: Int, millOptions: String*): TestCommandResult =
    TestCommandResult(tester.eval(Seq("-j", jobs.toString) ++ millOptions ++ Seq("scalatest/test", "core.test", "-fJ", report(tester).toString)))

  /** Mill's ticker is on, as at a user's console, so Mill puts its prefix on each line the test command writes. */
  private def runTestCommandAsAtTheConsole(tester: IntegrationTester): TestCommandResult =
    runTestCommandWithJobs(tester, 2, "--ticker", "true")

  /** The module holds only the given suites, each in its own fork group, in the order given. */
  private def holdOnlySuitesEachInItsOwnForkGroup(tester: IntegrationTester, testBodies: (String, String)*): Unit = {
    os.remove.all(samples(tester))
    os.makeDir.all(samples(tester))
    testBodies.foreach { case (suite, testBody) => writeSuite(tester, suite, testBody) }
    editBuild(
      tester,
      """Seq(Seq("samples.B1", "samples.B2"), Seq("samples.A1", "samples.A2"))""",
      testBodies.map { case (suite, _) => s"""Seq("samples.$suite")""" }.mkString("Seq(", ", ", ")")
    )
  }

  private def editBuild(tester: IntegrationTester, from: String, to: String): Unit = {
    val buildFile = tester.workspacePath / "build.mill"
    val build = os.read(buildFile)
    withClue(s"the build file holds no $from:\n$build\n") {
      build should include(from)
    }
    os.write.over(buildFile, build.replace(from, to))
  }

  private def turnOnTestParallelism(tester: IntegrationTester): Unit =
    editBuild(tester, "def testParallelism = false", "def testParallelism = true")

  private def turnOffTestSandboxing(tester: IntegrationTester): Unit =
    editBuild(tester, "def testForkGrouping", "def testSandboxWorkingDir = false\n    def testForkGrouping")

  private def turnOnBatchFrameworkTasks(tester: IntegrationTester): Unit =
    editBuild(tester, "def testForkGrouping", "def testBatchFrameworkTasks = true\n    def testForkGrouping")

  private val suites: Seq[String] = Seq("A1", "A2", "B1", "B2")

  private def infoMessages(node: ujson.Value): Seq[String] =
    nodes(node).flatMap(_.obj.get("output").toSeq.flatMap(_.arr)).filter(_("kind").str == "info").map(_("message").str)

  private val workerFolder: Regex = """[/\\](worker-\d+)(?:[/\\]|$)""".r

  private def eventLogFolders(tester: IntegrationTester): Seq[os.Path] =
    os.walk(tester.workspacePath / "out").filter(_.last == "scalatest-event-log.jsonl").map(_ / os.up)

  private def shouldRunEachForkGroupInExactlyOneWorker(json: ujson.Value): Unit = {
    Then("each of B1 and B2 is in the contents of a run whose forkGroup is 0, and in no run whose forkGroup is 1")
    runsOfForkGroup(json, 0).flatMap(suiteNames) should contain theSameElementsAs Seq("B1", "B2")
    And("each of A1 and A2 is in the contents of a run whose forkGroup is 1, and in no run whose forkGroup is 0")
    runsOfForkGroup(json, 1).flatMap(suiteNames) should contain theSameElementsAs Seq("A1", "A2")

    And("for each forkGroup, the module has exactly 1 run with that forkGroup")
    runs(json).map(_("forkGroup").num.toInt) shouldBe Seq(0, 1)
  }

  private def shouldRunEachForkGroupInItsOwnWorkers(json: ujson.Value): Unit = {
    Then("each of B1 and B2 is in the contents of at least one of the module's runs whose forkGroup is 0, and in no run whose forkGroup is 1")
    runsOfForkGroup(json, 0).flatMap(suiteNames).distinct should contain theSameElementsAs Seq("B1", "B2")
    And("each of A1 and A2 is in the contents of at least one of the module's runs whose forkGroup is 1, and in no run whose forkGroup is 0")
    runsOfForkGroup(json, 1).flatMap(suiteNames).distinct should contain theSameElementsAs Seq("A1", "A2")

    And("for each forkGroup, the module has either 1 or 2 runs with that forkGroup")
    runs(json).map(_("forkGroup").num.toInt).toSet shouldBe Set(0, 1)
    Seq(0, 1).foreach(forkGroup => runsOfForkGroup(json, forkGroup).size should (be >= 1 and be <= 2))
  }

  Feature("Every worker of a JVM module") {

    Scenario("testParallelism false, testBatchFrameworkTasks false, testSandboxWorkingDir true") {
      withTester("every-worker") { tester =>
        Given("the build, whose module holds suites A1, A2, B1 and B2")
        And("""the module's testForkGrouping is Seq(Seq("samples.B1", "samples.B2"), Seq("samples.A1", "samples.A2")) and its testParallelism is false""")
        When("the test command is invoked")
        val (result, json) = jsonOutputOf(tester)

        withClue(result.debugString) {
          shouldRunEachForkGroupInExactlyOneWorker(json)
        }

        And("the test command succeeds")
        shouldSucceed(result)
      }
    }

    Scenario("testParallelism true, testBatchFrameworkTasks false, testSandboxWorkingDir true") {
      withTester("every-worker") { tester =>
        Given("the build, whose module holds suites A1, A2, B1 and B2 in fork groups (B1, B2) and (A1, A2)")
        And("the module's testParallelism is true and its testSandboxWorkingDir is true")
        turnOnTestParallelism(tester)
        When("the test command is invoked")
        val (result, json) = jsonOutputWithJobs(tester, 2)

        withClue(result.debugString) {
          shouldRunEachForkGroupInItsOwnWorkers(json)
        }

        And("the test command succeeds")
        shouldSucceed(result)
      }
    }

    Scenario("testParallelism true, testBatchFrameworkTasks false, testSandboxWorkingDir false") {
      withTester("every-worker") { tester =>
        Given("the build, whose module holds suites A1, A2, B1 and B2 in fork groups (B1, B2) and (A1, A2)")
        And("the module's testParallelism is true and its testSandboxWorkingDir is false")
        turnOnTestParallelism(tester)
        turnOffTestSandboxing(tester)
        When("the test command is invoked")
        val (result, json) = jsonOutputWithJobs(tester, 2)

        withClue(result.debugString) {
          shouldRunEachForkGroupInItsOwnWorkers(json)
        }

        And("the test command succeeds")
        shouldSucceed(result)
      }
    }

    Scenario("testParallelism false, testBatchFrameworkTasks true, testSandboxWorkingDir true") {
      withTester("every-worker") { tester =>
        Given("the build, whose module holds suites A1, A2, B1 and B2 in fork groups (B1, B2) and (A1, A2)")
        And("the module's testParallelism is false and its testBatchFrameworkTasks is true")
        turnOnBatchFrameworkTasks(tester)
        When("the test command is invoked")
        val (result, json) = jsonOutputWithJobs(tester, 2)

        withClue(result.debugString) {
          shouldRunEachForkGroupInExactlyOneWorker(json)
        }

        And("the test command succeeds")
        shouldSucceed(result)
      }
    }

    Scenario("testParallelism true, testBatchFrameworkTasks true, testSandboxWorkingDir true") {
      withTester("every-worker") { tester =>
        Given("the build, whose module holds suites A1, A2, B1 and B2 in fork groups (B1, B2) and (A1, A2)")
        And("the module's testParallelism is true and its testBatchFrameworkTasks is true")
        turnOnTestParallelism(tester)
        turnOnBatchFrameworkTasks(tester)
        When("the test command is invoked")
        val (result, json) = jsonOutputWithJobs(tester, 2)

        withClue(result.debugString) {
          shouldRunEachForkGroupInExactlyOneWorker(json)
        }

        And("the test command succeeds")
        shouldSucceed(result)
      }
    }

    Scenario("A failing test in the second fork group") {
      withTester("every-worker") { tester =>
        Given("the build, whose module holds suites A1, A2, B1 and B2 in fork groups (B1, B2) and (A1, A2)")
        And("the module's testParallelism is true")
        turnOnTestParallelism(tester)
        And("A2's test t fails")
        writeSuite(tester, "A2", """fail("A2 fails")""")
        When("the test command is invoked")
        val (result, json) = jsonOutputWithJobs(tester, 2)

        Then("the JSON output holds a test t in suite A2 whose outcome is failed")
        val a2Tests = runs(json).flatMap(contents).filter(_("name").str == "A2").flatMap(contents)
        withClue(result.debugString) {
          a2Tests.map(test => (test("name").str, test.obj.get("outcome").map(_.str))) should contain(("t", Some("failed")))
        }

        And("each of A1, B1 and B2 is in the contents of one of the module's runs")
        runs(json).flatMap(suiteNames) should contain allOf ("A1", "B1", "B2")

        And("the test command fails")
        withClue(result.debugString) {
          result.succeeded shouldBe false
        }
      }
    }

    Scenario("Two workers in each fork group") {
      withTester("every-worker") { tester =>
        Given("the build, whose module holds suites A1, A2, B1 and B2 in fork groups (B1, B2) and (A1, A2)")
        And("the module's testParallelism is true")
        turnOnTestParallelism(tester)
        And("each suite's test t sleeps for 3 seconds, then sends an info message holding the system property user.dir")
        suites.foreach(suite => writeSuite(tester, suite, "Thread.sleep(3000); info(System.getProperty(\"user.dir\"))"))
        When("the test command is invoked as mill -j 4 scalatest/test core.test -fJ <report>")
        val (result, json) = jsonOutputWithJobs(tester, 4)

        Then("for each forkGroup, the module has exactly two runs with that forkGroup")
        withClue(result.debugString) {
          Seq(0, 1).map(forkGroup => runsOfForkGroup(json, forkGroup).size) shouldBe Seq(2, 2)
        }

        And("within each of the module's runs, every info message is a path holding one and the same folder worker-<i>")
        val workerFoldersOfRuns = runs(json).map { run =>
          val folders = infoMessages(run).map(message => workerFolder.findFirstMatchIn(message).map(_.group(1)))
          withClue(s"in the info messages ${infoMessages(run)}: ") {
            folders should not be empty
            folders.distinct should have size 1
            folders.head shouldBe defined
          }
          (run("forkGroup").num.toInt, run("number").num.toInt, folders.head.getOrElse(""))
        }

        And("of the two runs with each forkGroup, the one with the lower number has the lower <i>")
        Seq(0, 1).foreach { forkGroup =>
          val indexesInNumberOrder = workerFoldersOfRuns.collect { case (`forkGroup`, number, folder) => (number, folder.stripPrefix("worker-").toInt) }.sortBy(_._1).map(_._2)
          indexesInNumberOrder.distinct should have size 2
          indexesInNumberOrder shouldBe indexesInNumberOrder.sorted
        }
      }
    }

    Scenario("The first fork group's run ends last") {
      withTester("every-worker") { tester =>
        Given("the build, whose module holds suites A1, A2, B1 and B2 in fork groups (B1, B2) and (A1, A2), with testParallelism false")
        And("B1's test t sleeps for 3 seconds before it succeeds")
        writeSuite(tester, "B1", "Thread.sleep(3000)")
        When("the test command is invoked")
        val (_, json) = jsonOutputOf(tester)

        Then("the module's runs are, in order, a run whose number is 1 and forkGroup is 0, then a run whose number is 2 and forkGroup is 1")
        runs(json).map(run => (run("number").num.toInt, run("forkGroup").num.toInt)) shouldBe Seq((1, 0), (2, 1))

        And("the run whose number is 1 holds suites B1 and B2, and the run whose number is 2 holds suites A1 and A2")
        runs(json).map(suiteNames) shouldBe Seq(Seq("B1", "B2"), Seq("A1", "A2"))
      }
    }
  }

  Feature("Each worker of a JVM module has its own event log, output and time") {

    Scenario("Each worker writes its event log in its own processFolder") {
      withTester("every-worker") { tester =>
        Given("the build, whose module holds suites A1, A2, B1 and B2 in fork groups (B1, B2) and (A1, A2), with testParallelism false")
        When("the test command is invoked")
        val (result, _) = jsonOutputOf(tester)

        Then("an event log file is in each of the two processFolders Mill's testForked lays out for the fork groups, and nowhere else")
        withClue(result.debugString) {
          eventLogFolders(tester).map(_.last) should contain theSameElementsAs Seq("group-0-samples.B1", "group-1-samples.A1")
        }

        And("Mill started a worker in each of those processFolders")
        eventLogFolders(tester).filterNot(folder => os.exists(folder / "testargs")) shouldBe empty
      }
    }

    Scenario("Under test parallelism, with test sandboxing off, each worker writes its event log in its own processFolder") {
      withTester("every-worker") { tester =>
        Given("the build, whose module holds suites A1, A2, B1 and B2 in fork groups (B1, B2) and (A1, A2)")
        And("the module's testParallelism is true and its testSandboxWorkingDir is false")
        turnOnTestParallelism(tester)
        turnOffTestSandboxing(tester)
        And("each suite's test t sleeps for 3 seconds, so each fork group's two workers each claim a class")
        suites.foreach(suite => writeSuite(tester, suite, "Thread.sleep(3000)"))
        When("the test command is invoked as mill -j 4 scalatest/test core.test -fJ <report>")
        val (result, _) = jsonOutputWithJobs(tester, 4)

        Then("an event log file is in each worker's own processFolder, worker-0 and worker-1 of each fork group's folder, and nowhere else")
        withClue(result.debugString) {
          eventLogFolders(tester).map(folder => s"${(folder / os.up).last}/${folder.last}") should contain theSameElementsAs Seq(
            "group-0-samples.B1/worker-0",
            "group-0-samples.B1/worker-1",
            "group-1-samples.A1/worker-0",
            "group-1-samples.A1/worker-1"
          )
        }

        And("Mill started a worker in each of those processFolders")
        eventLogFolders(tester).filterNot(folder => os.exists(folder / "testargs")) shouldBe empty
      }
    }

    Scenario("Under testBatchFrameworkTasks, each fork group's one worker writes its event log in the group's processFolder, and no queue is laid out") {
      withTester("every-worker") { tester =>
        Given("the build, whose module holds suites A1, A2, B1 and B2 in fork groups (B1, B2) and (A1, A2)")
        And("the module's testParallelism is true and its testBatchFrameworkTasks is true")
        turnOnTestParallelism(tester)
        turnOnBatchFrameworkTasks(tester)
        When("the test command is invoked as mill -j 4 scalatest/test core.test -fJ <report>")
        val (result, _) = jsonOutputWithJobs(tester, 4)

        Then("an event log file is in each of the two processFolders Mill's testForked lays out for the fork groups, and nowhere else")
        withClue(result.debugString) {
          eventLogFolders(tester).map(_.last) should contain theSameElementsAs Seq("group-0-samples.B1", "group-1-samples.A1")
        }

        And("Mill started a worker in each of those processFolders")
        eventLogFolders(tester).filterNot(folder => os.exists(folder / "testargs")) shouldBe empty

        And("neither processFolder holds a test queue or a claim folder")
        eventLogFolders(tester).flatMap(folder => Seq(folder / "test-classes", folder / "claim")).filter(os.exists(_)) shouldBe empty
      }
    }

    Scenario("Under testBatchFrameworkTasks, each worker's stdout is on that worker's run alone, and none reaches the console") {
      withTester("every-worker") { tester =>
        Given("the build, whose module holds suites A1, A2, B1 and B2 in fork groups (B1, B2) and (A1, A2)")
        And("the module's testParallelism is false and its testBatchFrameworkTasks is true")
        turnOnBatchFrameworkTasks(tester)
        And("B1's test t writes the line from B1 to stdout, and A1's test t writes the line from A1 to stdout")
        writeSuite(tester, "B1", """println("from B1")""")
        writeSuite(tester, "A1", """println("from A1")""")
        When("the test command is invoked")
        val (result, json) = jsonOutputOf(tester)

        Then("the stdout of the run whose forkGroup is 0 holds from B1 and not from A1")
        withClue(result.debugString) {
          runsOfForkGroup(json, 0).flatMap(stdoutLines) should (contain("from B1") and not contain "from A1")
        }

        And("the stdout of the run whose forkGroup is 1 holds from A1 and not from B1")
        runsOfForkGroup(json, 1).flatMap(stdoutLines) should (contain("from A1") and not contain "from B1")

        And("no console line outside the human-readable output holds either line")
        result.consoleLinesOutsideHumanReadableOutput.filter(line => line.contains("from B1") || line.contains("from A1")) shouldBe empty
      }
    }

    Scenario("Two workers each write to stdout") {
      withTester("every-worker") { tester =>
        Given("the build, whose module holds suites A1 and B1")
        And("""the module's testForkGrouping is Seq(Seq("samples.B1"), Seq("samples.A1")) and its testParallelism is false""")
        And("B1's test t writes the lines B 1 to B 3 to stdout, and A1's test t writes the lines A 1 to A 3 to stdout")
        holdOnlySuitesEachInItsOwnForkGroup(
          tester,
          "B1" -> """(1 to 3).foreach(i => println("B " + i))""",
          "A1" -> """(1 to 3).foreach(i => println("A " + i))"""
        )
        When("the test command is invoked")
        val (result, json) = jsonOutputWithJobs(tester, 2)

        Then("the stdout text of the output of the run whose forkGroup is 0 holds the lines B 1 to B 3, in order, and no line beginning A ")
        val forkGroup0Lines = runsOfForkGroup(json, 0).flatMap(stdoutLines)
        withClue(result.debugString) {
          forkGroup0Lines.filter(_.startsWith("B ")) shouldBe (1 to 3).map(i => s"B $i")
          forkGroup0Lines.filter(_.startsWith("A ")) shouldBe empty
        }

        And("the stdout text of the output of the run whose forkGroup is 1 holds the lines A 1 to A 3, in order, and no line beginning B ")
        val forkGroup1Lines = runsOfForkGroup(json, 1).flatMap(stdoutLines)
        withClue(result.debugString) {
          forkGroup1Lines.filter(_.startsWith("A ")) shouldBe (1 to 3).map(i => s"A $i")
          forkGroup1Lines.filter(_.startsWith("B ")) shouldBe empty
        }
      }
    }

    Scenario("Under test parallelism, each worker's stdout and stderr are on that worker's run alone, and none reaches the console") {
      withTester("every-worker") { tester =>
        Given("the build, whose module holds suites A1, A2, B1 and B2 in fork groups (B1, B2) and (A1, A2)")
        And("the module's testParallelism is true")
        turnOnTestParallelism(tester)
        And("each suite's test t sleeps for 3 seconds, so each fork group's two workers each claim a class")
        And("B1's test t then writes the line out from B1 to stdout, and A1's test t writes the line err from A1 to stderr")
        writeSuite(tester, "B1", """Thread.sleep(3000); println("out from B1")""")
        writeSuite(tester, "A1", """Thread.sleep(3000); System.err.println("err from A1")""")
        Seq("B2", "A2").foreach(suite => writeSuite(tester, suite, "Thread.sleep(3000)"))
        When("the test command is invoked as mill -j 4 scalatest/test core.test -fJ <report>")
        val (result, json) = jsonOutputWithJobs(tester, 4)

        Then("the stdout of a run whose forkGroup is 0 holds out from B1, and no run whose forkGroup is 1 holds it")
        withClue(result.debugString) {
          runsOfForkGroup(json, 0).flatMap(stdoutLines) should contain("out from B1")
          runsOfForkGroup(json, 1).flatMap(stdoutLines) should not contain "out from B1"
        }

        And("every run whose stdout holds out from B1 holds suite B1")
        runs(json).filter(stdoutLines(_).contains("out from B1")).foreach(run => suiteNames(run) should contain("B1"))

        And("the stderr of a run whose forkGroup is 1 holds err from A1, and no run whose forkGroup is 0 holds it")
        runsOfForkGroup(json, 1).flatMap(stderrLines) should contain("err from A1")
        runsOfForkGroup(json, 0).flatMap(stderrLines) should not contain "err from A1"

        And("every run whose stderr holds err from A1 holds suite A1")
        runs(json).filter(stderrLines(_).contains("err from A1")).foreach(run => suiteNames(run) should contain("A1"))

        And("no console line outside the human-readable output holds either line")
        result.consoleLinesOutsideHumanReadableOutput.filter(line => line.contains("out from B1") || line.contains("err from A1")) shouldBe empty
      }
    }

    Scenario("The module is timed from before its first worker starts until its last worker has ended") {
      withTester("every-worker") { tester =>
        Given("the build, whose module holds suites A1, A2, B1 and B2 in fork groups (B1, B2) and (A1, A2), with testParallelism false")
        When("the test command is invoked")
        val (_, json) = jsonOutputOf(tester)

        Then("the module starts no later than its first run, and ends no earlier than its last run")
        val module = node(json, "module", "core.test")
        module("startedAt").num should be <= runs(json).map(_("startedAt").num).min
        module("endedAt").num should be >= runs(json).map(_("endedAt").num).max
      }
    }
  }

  Feature("Runs in the human-readable output") {

    Scenario("One worker writes to stdout") {
      withTester("every-worker") { tester =>
        Given("the build, whose module holds suites Zeta and Alpha")
        And("""the module's testForkGrouping is Seq(Seq("samples.Zeta"), Seq("samples.Alpha")) and its testParallelism is false""")
        And("Zeta's test t writes the line hello to stdout")
        And("nothing else in either run writes to stdout or stderr")
        holdOnlySuitesEachInItsOwnForkGroup(tester, "Zeta" -> """println("hello")""", "Alpha" -> "()")
        When("the test command is invoked, with Mill's ticker on")
        val result = runTestCommandAsAtTheConsole(tester)

        Then("the human-readable output, apart from its summary line, is Alpha directly under the module, then Run 1: holding Zeta and the line hello")
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
      withTester("every-worker") { tester =>
        Given("the build, whose module holds suites Zeta and Alpha")
        And("""the module's testForkGrouping is Seq(Seq("samples.Zeta"), Seq("samples.Alpha")) and its testParallelism is false""")
        And("Zeta's test t fails")
        And("nothing in either run writes to stdout or stderr")
        holdOnlySuitesEachInItsOwnForkGroup(tester, "Zeta" -> """fail("Zeta fails")""", "Alpha" -> "()")
        When("the test command is invoked, with Mill's ticker on")
        val result = runTestCommandAsAtTheConsole(tester)

        Then("the human-readable output holds no line beginning Run ")
        withClue(result.debugString) {
          result.treeLines.filter(_.trim.startsWith("Run ")) shouldBe empty
        }

        And("the line   Alpha: comes before the line   Zeta:, each directly under [core.test]")
        withClue(result.debugString) {
          result.treeLines.head shouldBe "[core.test]"
          result.treeLines.filter(line => line.startsWith("  ") && !line.startsWith("   ")) shouldBe Seq("  Alpha:", "  Zeta:")
        }

        And("the test command fails")
        withClue(result.debugString) {
          result.succeeded shouldBe false
        }
      }
    }
  }

  Feature("A module with no test class") {

    Scenario("No test class") {
      withTester("every-worker") { tester =>
        Given("the build, whose module holds no test class")
        os.remove.all(samples(tester))
        When("the test command is invoked")
        val (result, json) = jsonOutputOf(tester)

        Then("the module's runs are exactly one run, whose number is 1 and forkGroup is 0, whose contents is [] and which has no outcome")
        runs(json).map(run => (run("number").num.toInt, run("forkGroup").num.toInt, contents(run), run.obj.contains("outcome"))) shouldBe Seq(
          (1, 0, Nil, false)
        )

        And("the human-readable output, apart from its summary line, is [core.test]")
        result.treeLines shouldBe Seq("[core.test]")

        And("the test command succeeds")
        shouldSucceed(result)
      }
    }

    Scenario("Under testBatchFrameworkTasks, a module with no fork group") {
      withTester("every-worker") { tester =>
        Given("the build, whose module holds suites A1, A2, B1 and B2, and whose testForkGrouping is Seq()")
        editBuild(tester, """Seq(Seq("samples.B1", "samples.B2"), Seq("samples.A1", "samples.A2"))""", "Seq()")
        And("the module's testBatchFrameworkTasks is true")
        turnOnBatchFrameworkTasks(tester)
        When("the test command is invoked")
        val (result, json) = jsonOutputOf(tester)

        Then("the module's runs are exactly one run, whose number is 1 and forkGroup is 0, whose contents is [] and which has no outcome")
        withClue(result.debugString) {
          runs(json).map(run => (run("number").num.toInt, run("forkGroup").num.toInt, contents(run), run.obj.contains("outcome"))) shouldBe Seq(
            (1, 0, Nil, false)
          )
        }

        And("the test command succeeds")
        shouldSucceed(result)
      }
    }
  }

  Feature("A worker that cannot start") {

    Scenario("The second of three fork groups' worker cannot start") {
      withTester("every-worker") { tester =>
        Given("the build, whose module holds suites A1, A2, B1 and B2")
        And("""the module's testForkGrouping is Seq(Seq("samples.B1", "samples.B2"), Seq("samples.A1"), Seq("samples.A2")) and its testParallelism is false""")
        val buildFile = tester.workspacePath / "build.mill"
        os.write.over(
          buildFile,
          os.read(buildFile).replace(
            """Seq(Seq("samples.B1", "samples.B2"), Seq("samples.A1", "samples.A2"))""",
            """Seq(Seq("samples.B1", "samples.B2"), Seq("samples.A1"), Seq("samples.A2"))"""
          )
        )
        And("B1's test t writes a testargs file into the second fork group's processFolder, so Mill cannot start a worker there")
        writeSuite(
          tester,
          "B1",
          """val testargs = java.nio.file.Paths.get("..", "..", "samples.A1", "testargs")
            |    java.nio.file.Files.createDirectories(testargs.getParent)
            |    java.nio.file.Files.write(testargs, Array.emptyByteArray)""".stripMargin
        )
        When("the test command is invoked")
        val (result, json) = jsonOutputOf(tester)

        Then("the root's failures are exactly one, naming the module and its worker that could not start")
        val failures = json("failures").arr.map(_("message").str)
        withClue(result.debugString) {
          failures should have size 1
          failures.head should startWith("Starting worker 0 of fork group 1 of core.test failed: ")
        }

        And("the module's runs are exactly a run whose forkGroup is 0 and which holds suites B1 and B2, then a run whose forkGroup is 2 and which holds suite A2")
        runs(json).map(run => (run("forkGroup").num.toInt, suiteNames(run))) shouldBe Seq((0, Seq("B1", "B2")), (2, Seq("A2")))

        And("the test command fails")
        withClue(result.debugString) {
          result.succeeded shouldBe false
        }
      }
    }
  }

  Feature("Fork groups that would share a processFolder") {

    Scenario("Two fork groups each hold only the same class") {
      withTester("every-worker") { tester =>
        Given("the build, whose module holds suites A1, A2, B1 and B2")
        And("""the module's testForkGrouping is Seq(Seq("samples.A1"), Seq("samples.A1")) and its testParallelism is false""")
        val buildFile = tester.workspacePath / "build.mill"
        os.write.over(
          buildFile,
          os.read(buildFile).replace(
            """Seq(Seq("samples.B1", "samples.B2"), Seq("samples.A1", "samples.A2"))""",
            """Seq(Seq("samples.A1"), Seq("samples.A1"))"""
          )
        )
        When("the test command is invoked")
        val (result, json) = jsonOutputOf(tester)

        Then("the root's failures are exactly Laying out the workers of core.test failed: fork groups 0 and 1 share the processFolder samples.A1")
        json("failures").arr.map(_("message").str) shouldBe Seq(
          "Laying out the workers of core.test failed: fork groups 0 and 1 share the processFolder samples.A1"
        )

        And("the module has no run, and no startedAt, endedAt or duration")
        runs(json) shouldBe empty
        shouldHaveNoKeys(node(json, "module", "core.test"), "startedAt", "endedAt", "duration")

        And("the test command fails before any worker starts")
        shouldFailBeforeAnyTestStarts(tester, result)
      }
    }
  }
}
