package org.scalatest.mill

import mill.testkit.IntegrationTester
import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

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

  private def stdoutLines(run: ujson.Value): Seq[String] =
    run.obj.get("output").toSeq.flatMap(_.arr).filter(_("kind").str == "stdio").filter(_("stream").str == "stdout").map(_("text").str.trim)

  private def jsonOutputOf(tester: IntegrationTester): (TestCommandResult, ujson.Value) = {
    val result = runTestCommand(tester, "core.test", "-fJ", report(tester).toString)
    (result, withClue(result.debugString)(jsonOutput(report(tester))))
  }

  Feature("Every worker of a JVM module") {

    Scenario("AC-001 testParallelism false, testBatchFrameworkTasks false, testSandboxWorkingDir true") {
      withTester("every-worker") { tester =>
        Given("the build, whose module holds suites A1, A2, B1 and B2")
        And("""the module's testForkGrouping is Seq(Seq("samples.B1", "samples.B2"), Seq("samples.A1", "samples.A2")) and its testParallelism is false""")
        When("the test command is invoked")
        val (result, json) = jsonOutputOf(tester)

        Then("each of B1 and B2 is in the contents of a run whose forkGroup is 0, and in no run whose forkGroup is 1")
        runsOfForkGroup(json, 0).flatMap(suiteNames) should contain theSameElementsAs Seq("B1", "B2")
        And("each of A1 and A2 is in the contents of a run whose forkGroup is 1, and in no run whose forkGroup is 0")
        runsOfForkGroup(json, 1).flatMap(suiteNames) should contain theSameElementsAs Seq("A1", "A2")

        And("for each forkGroup, the module has exactly 1 run with that forkGroup")
        runs(json).map(_("forkGroup").num.toInt) shouldBe Seq(0, 1)

        And("the test command succeeds")
        shouldSucceed(result)
      }
    }

    Scenario("AC-003 The first fork group's run ends last") {
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
        val eventLogFolders = os.walk(tester.workspacePath / "out").filter(_.last == "scalatest-event-log.jsonl").map(_ / os.up)
        withClue(result.debugString) {
          eventLogFolders.map(_.last) should contain theSameElementsAs Seq("group-0-samples.B1", "group-1-samples.A1")
        }

        And("Mill started a worker in each of those processFolders")
        eventLogFolders.filterNot(folder => os.exists(folder / "testargs")) shouldBe empty
      }
    }

    Scenario("Each worker's stdout is on that worker's run alone") {
      withTester("every-worker") { tester =>
        Given("the build, whose module holds suites A1, A2, B1 and B2 in fork groups (B1, B2) and (A1, A2), with testParallelism false")
        And("B1's test t writes the line from B1 to stdout, and A1's test t writes the line from A1 to stdout")
        writeSuite(tester, "B1", """println("from B1")""")
        writeSuite(tester, "A1", """println("from A1")""")
        When("the test command is invoked")
        val (_, json) = jsonOutputOf(tester)

        Then("the stdout of the run whose forkGroup is 0 holds from B1 and not from A1")
        runsOfForkGroup(json, 0).flatMap(stdoutLines) should (contain("from B1") and not contain "from A1")

        And("the stdout of the run whose forkGroup is 1 holds from A1 and not from B1")
        runsOfForkGroup(json, 1).flatMap(stdoutLines) should (contain("from A1") and not contain "from B1")
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

  Feature("A worker given no task") {

    Scenario("AC-005 No test class") {
      withTester("every-worker") { tester =>
        Given("the build, whose module holds no test class")
        os.remove.all(samples(tester))
        val buildFile = tester.workspacePath / "build.mill"
        os.write.over(buildFile, os.read.lines(buildFile).filterNot(_.contains("testForkGrouping")).mkString("", "\n", "\n"))
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
  }

  Feature("A worker that cannot start") {

    Scenario("The second fork group's worker cannot start") {
      withTester("every-worker") { tester =>
        Given("the build, whose module holds suites A1, A2, B1 and B2 in fork groups (B1, B2) and (A1, A2), with testParallelism false")
        And("B1's test t writes a testargs file into the second fork group's processFolder, so Mill cannot start a worker there")
        writeSuite(
          tester,
          "B1",
          """java.nio.file.Files.write(java.nio.file.Paths.get("..", "..", "group-1-samples.A1", "testargs"), Array.emptyByteArray)"""
        )
        When("the test command is invoked")
        val (result, json) = jsonOutputOf(tester)

        Then("the root's failures are exactly one, naming the module and its worker that could not start")
        val failures = json("failures").arr.map(_("message").str)
        withClue(result.debugString) {
          failures should have size 1
          failures.head should startWith("Starting worker 0 of fork group 1 of core.test failed: ")
        }

        And("the module's runs are exactly one run, whose forkGroup is 0 and which holds suites B1 and B2")
        runs(json).map(run => (run("forkGroup").num.toInt, suiteNames(run))) shouldBe Seq((0, Seq("B1", "B2")))

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
