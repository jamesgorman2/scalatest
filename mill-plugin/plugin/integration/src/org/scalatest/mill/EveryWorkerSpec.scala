package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should
import org.scalatest.mill.tags.CoreTag
import org.scalatest.mill.tags.Workers

@Workers
final class EveryWorkerSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with EveryWorkerFixture {

  private def infoMessages(node: JsonNode): Seq[String] =
    node.nodes.flatMap(_.output).filter(_.kind == OutputItemKind.Info).map(_.message)

  Feature("Every worker of a JVM module") {

    Scenario("Without test parallelism, each fork group runs its suites in one worker of its own") {
      inSharedEveryWorkerBuild(sharedUnchangedEveryWorkerBuild) { build =>
        givenTheModuleHoldsTwoForkGroups()
        And("test parallelism is off")
        When("the test command runs the module with two jobs")
        val (result, json) = build.resultAndJsonOutputOfTestCommand(jobs = 2)

        withClue(result.debugString) {
          shouldRunEachForkGroupInExactlyOneWorker(json)
        }

        And("the test command succeeds")
        shouldSucceed(result)
      }
    }

    Scenario("Under test parallelism, each fork group runs its suites in one or two workers of its own", CoreTag) {
      inSharedEveryWorkerBuild(sharedEveryWorkerBuildWithTestParallelism) { build =>
        givenTheModuleHoldsTwoForkGroups()
        And("test parallelism is on, and test sandboxing is on by default")
        When("the test command runs the module with two jobs")
        val (result, json) = build.resultAndJsonOutputOfTestCommand(jobs = 2)

        withClue(result.debugString) {
          shouldRunEachForkGroupInItsOwnWorkers(json)
        }

        And("the test command succeeds")
        shouldSucceed(result)
      }
    }

    Scenario("A test failing in the second fork group fails the test command, and every other suite still runs") {
      inSharedEveryWorkerBuild(sharedEveryWorkerBuildWithTestParallelism) { build =>
        givenTheModuleHoldsTwoForkGroups()
        And("test parallelism is on")
        And("A2's test fails")
        build.writeSuite("A2", """fail("A2 fails")""")
        When("the test command runs the module with two jobs")
        val (result, json) = build.resultAndJsonOutputOfTestCommand(jobs = 2)

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

    Scenario("A fork group whose two classes must run at the same time runs in its first two workers, numbered in that order") {
      inSharedEveryWorkerBuild(sharedEveryWorkerBuildWithTestParallelism) { build =>
        givenTheModuleHoldsTwoForkGroups()
        And("test parallelism is on")
        And("A1's and A2's tests each send their working directory as an info message, then wait up to 30 seconds for the other to start")
        val markers = build.emptyMarkersFolder()
        build.writeSuiteThatWaitsFor("A1", "A2", markers)
        build.writeSuiteThatWaitsFor("A2", "A1", markers)
        When("the test command runs the module with four jobs")
        val (result, json) = build.resultAndJsonOutputOfTestCommand(jobs = 4)

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
      inSharedEveryWorkerBuild(sharedEveryWorkerBuildWithBatchFrameworkTasks) { build =>
        givenTheModuleHoldsTwoForkGroups()
        And("testBatchFrameworkTasks is on and test parallelism is off, so the two fork groups' workers run at the same time")
        And("B1's test sleeps before it succeeds, so the first fork group's run ends last")
        build.writeSuite("B1", "Thread.sleep(3000)")
        When("the test command runs the module with two jobs")
        val (result, json) = build.resultAndJsonOutputOfTestCommand(jobs = 2)

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

  Feature("A module with no test class") {

    Scenario("A module with no test class has one empty run, which the human-readable output does not show") {
      withEveryWorkerBuild { build =>
        Given("a module holding no test class")
        build.removeSuites()
        And("test parallelism is on, and the module has no testForkGrouping")
        build.turnOnTestParallelism()
        build.removeForkGrouping()
        When("the test command runs the module with two jobs")
        val (result, json) = build.resultAndJsonOutputOfTestCommand(jobs = 2)

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
        val (result, json) = build.resultAndJsonOutputOfTestCommand()

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
        val (result, json) = build.resultAndJsonOutputOfTestCommand()

        Then("the module node core.test holds exactly one failure, whose message begins Starting worker 0 of fork group 1 failed:")
        val failures = json.node(NodeKind.Module, "core.test").failureMessages
        withClue(result.debugString) {
          failures should have size 1
          failures.head should startWith("Starting worker 0 of fork group 1 failed: ")
        }

        And("the JSON output's root holds no failure")
        withClue(result.debugString) {
          json.failureMessages shouldBe empty
        }

        And("the module's runs are the first fork group's, holding B1 and B2, then the third's, holding A2")
        runs(json).map(run => (run.forkGroup, run.contentNames)) shouldBe Seq((Some(0), Seq("B1", "B2")), (Some(2), Seq("A2")))

        And("the test command fails")
        shouldFail(result)
      }
    }
  }
}
