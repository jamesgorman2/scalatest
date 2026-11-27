package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

private enum MillFlags(val arguments: Seq[String]) {
  case Empty extends MillFlags(Nil)
  case KeepGoing extends MillFlags(Seq("--keep-going"))

  def description: String = if (arguments.isEmpty) "none" else arguments.mkString(" ")
}

final class EverySelectedModuleRunsSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with SeveralModulesFixture {

  private def addLateTestCompilingAfterAlpha1Fast(build: FixtureBuild): Unit = {
    Given("the build also has the JVM test module late.test, which tests with com.edc::scalatest at the release under test and holds the suite Late1, whose class is samples.Late1")
    And("Late1 is an AnyFunSuite whose test fast creates the file Late1.fast in <markers>")
    And("late.test's compile depends on a task that ends once the file Alpha1.fast exists in <markers>, and fails if that file does not exist within 120 seconds")
    // The wait is in late's compile, which late.test's compile depends on. The scenario therefore also fails where the
    // check compiles a module that late.test depends on through moduleDeps.
    build.appendToBuild(
      """object late extends SampleModule {
        |  def awaitAlpha1Fast: Task[Unit] = Task.Anon {
        |    val marker = BuildCtx.workspaceRoot / "markers" / "Alpha1.fast"
        |    val deadline = System.nanoTime() + 120L * 1000000000L
        |    while (!os.exists(marker) && System.nanoTime() < deadline) {
        |      Thread.sleep(100)
        |    }
        |    if (os.exists(marker)) mill.api.Result.Success(()) else mill.api.Result.Failure(s"$marker does not exist after 120 seconds")
        |  }
        |
        |  def compile: T[mill.javalib.api.CompilationResult] = Task {
        |    awaitAlpha1Fast()
        |    super.compile()
        |  }
        |
        |  object test extends SampleTests
        |}""".stripMargin
    )
  }

  private def addBrokenTest(build: FixtureBuild)(testModuleMembers: String*): Unit =
    build.appendToBuild(
      s"object broken extends SampleModule {\n  object test extends SampleTests {\n${testModuleMembers.map("    " + _).mkString("\n")}\n  }\n}"
    )

  private def addBrokenTestThatDoesNotCompile(build: FixtureBuild): Unit = {
    Given("the build also has the JVM test module broken.test, which tests with com.edc::scalatest at the release under test and whose sources do not compile")
    addBrokenTest(build)()
    replaceInSuite(build, "broken", "Broken1")("""test("fast") {}""", """test("fast") { notDefinedAnywhere }""")
  }

  private def setForkGroupingOfBetaTest(build: FixtureBuild, forkGrouping: String): Unit =
    addToBetaTest(build, s"def testForkGrouping = Task { $forkGrouping }")

  private def shouldBeAbortedWithOneFailureContaining(json: JsonNode, module: String, text: String): Unit = {
    val node = moduleNode(json, module)
    runNodesOf(json, module) shouldBe empty
    node.outcome shouldBe Some("aborted")
    node.failureMessages should have size 1
    node.failureMessages.head should include(text)
  }

  private def brokenTestShouldAbortWhileAlphaAndBetaRun(build: FixtureBuild, result: TestCommandResult, json: JsonNode, failureText: String): Unit = {
    withClue(result.debugString) {
      Then("the files Alpha1.fast, Alpha1.slow, Beta1.fast and Beta1.slow exist in <markers>")
      build.markers should contain allOf ("Alpha1.fast", "Alpha1.slow", "Beta1.fast", "Beta1.slow")

      And("the JSON output's root holds exactly the module nodes alpha.test, beta.test and broken.test, in that order")
      moduleNodeNames(json) shouldBe Seq("alpha.test", "beta.test", "broken.test")

      And(s"the module node broken.test holds no run node, its outcome is aborted, and its failures holds exactly one entry, whose message contains $failureText")
      shouldBeAbortedWithOneFailureContaining(json, "broken.test", failureText)

      And("the module nodes alpha.test and beta.test have no outcome and no failures")
      Seq("alpha.test", "beta.test").foreach(module => shouldHaveNoKeys(moduleNode(json, module), "outcome", "failures"))

      And("the JSON output's root has no outcome and no failures")
      shouldHaveNoKeys(json, "outcome", "failures")
    }

    And("the test command fails")
    shouldFail(result)
  }

  Feature("Every selected module runs, whatever happens to the others") {

    for (millFlags <- MillFlags.values) {

      Scenario(s"A test of alpha.test fails, flags ${millFlags.description}") {
        withSeveralModules { build =>
          Given("Alpha1's test slow fails")
          replaceInSuite(build, "alpha", "Alpha1")("""test("slow") {}""", """test("slow") { fail("fails as the scenario asks") }""")
          val (result, _) = runParsingJsonOutput(build, millFlags.arguments*)("alpha.test", "beta.test")

          Then("the files Beta1.fast and Beta1.slow exist in <markers>")
          withClue(result.debugString) {
            build.markers should contain allOf ("Beta1.fast", "Beta1.slow")
          }

          And("the test command fails")
          shouldFail(result)
        }
      }
    }

    Scenario(s"broken.test does not compile, flags ${MillFlags.KeepGoing.description}") {
      withSeveralModules { build =>
        addBrokenTestThatDoesNotCompile(build)
        val (result, json) = runParsingJsonOutput(build, MillFlags.KeepGoing.arguments*)("alpha.test", "broken.test", "beta.test")

        brokenTestShouldAbortWhileAlphaAndBetaRun(build, result, json, "broken.test.compile")
      }
    }

    val causesAndFailureTexts = Seq(
      (
        "whose mvnDeps holds com.example::absent:0.0.1, which no repository the build uses holds",
        """def mvnDeps = Seq(mvn"com.edc::scalatest::3.2.20", mvn"com.example::absent:0.0.1")""",
        "com.example:absent_3:0.0.1"
      ),
      (
        "whose generatedSources task throws an exception",
        """def generatedSources: T[Seq[PathRef]] = Task { sys.error("generatedSources throws as the scenario asks") }""",
        "broken.test.generatedSources"
      )
    )

    for ((cause, testModuleMember, failureText) <- causesAndFailureTexts) {
      Scenario(s"broken.test's tests cannot start: $cause") {
        withSeveralModules { build =>
          Given(s"the build also has the JVM test module broken.test, which tests with com.edc::scalatest at the release under test and $cause")
          addBrokenTest(build)(testModuleMember)
          val (result, json) = runParsingJsonOutput(build)("alpha.test", "broken.test", "beta.test")

          brokenTestShouldAbortWhileAlphaAndBetaRun(build, result, json, failureText)
        }
      }
    }

    Scenario("broken.test's failure in the human-readable output") {
      withSeveralModules { build =>
        addBrokenTestThatDoesNotCompile(build)
        When("mill -j 2 scalatest/test alpha.test broken.test beta.test -oH is invoked")
        val result = runTestCommand(build)("alpha.test", "broken.test", "beta.test", "-oH")
        val lines = result.treeLines
        val colourMarkedLines = result.colourMarkedTreeLines
        val moduleLine = lines.indexOf("[broken.test]")

        withClue(result.debugString) {
          Then("in the human-readable output on stdout, the line after the line [broken.test] is *** MODULE ABORTED ***, in red and indented one level more than [broken.test]")
          moduleLine should be >= 0
          colourMarkedLines.lift(moduleLine + 1) shouldBe Some("  {red}*** MODULE ABORTED ***{reset}")

          And("each line between *** MODULE ABORTED *** and the next line indented no more than *** MODULE ABORTED *** is in red and indented one level more than *** MODULE ABORTED ***")
          val failureLines = lines.zip(colourMarkedLines).drop(moduleLine + 2).takeWhile { case (line, _) => TestCommandResult.indentationOf(line) > 2 }
          failureLines.foreach { case (line, colourMarkedLine) =>
            line shouldBe s"    ${line.trim}"
            colourMarkedLine shouldBe s"    {red}${line.trim}{reset}"
          }

          And("there is at least one such line, and one of them contains broken.test.compile")
          failureLines.map(_._1).filter(_.contains("broken.test.compile")) should not be empty
        }

        And("the test command fails")
        shouldFail(result)
      }
    }

    Scenario("Two of beta.test's fork groups each hold only samples.Beta1") {
      withSeveralModules { build =>
        Given("beta.test's testForkGrouping gives two fork groups, each holding only samples.Beta1")
        setForkGroupingOfBetaTest(build, """Seq(Seq("samples.Beta1"), Seq("samples.Beta1"))""")
        val (result, json) = runParsingJsonOutput(build)("alpha.test", "beta.test")

        withClue(result.debugString) {
          Then("the files Alpha1.fast and Alpha1.slow exist in <markers>, and neither Beta1.fast nor Beta1.slow does")
          build.markers should contain allOf ("Alpha1.fast", "Alpha1.slow")
          build.markers should contain noneOf ("Beta1.fast", "Beta1.slow")

          And("the module node beta.test holds no run node, its outcome is aborted, and its failures holds exactly one entry, whose message contains samples.Beta1 and does not contain beta.test")
          shouldBeAbortedWithOneFailureContaining(json, "beta.test", "samples.Beta1")
          moduleNode(json, "beta.test").failureMessages.head should not include "beta.test"

          And("the module node beta.test has no startedAt, endedAt or duration")
          shouldHaveNoKeys(moduleNode(json, "beta.test"), "startedAt", "endedAt", "duration")

          And("Mill started no worker in beta.test's module folder")
          build.anyTestWorkerStartedIn(build.modulesFolder / "beta.test") shouldBe false

          And("the module node alpha.test has no outcome and no failures")
          shouldHaveNoKeys(moduleNode(json, "alpha.test"), "outcome", "failures")

          And("the JSON output's root has no outcome and no failures")
          shouldHaveNoKeys(json, "outcome", "failures")
        }

        And("the test command fails")
        shouldFail(result)
      }
    }

    Scenario("late.test compiles only after a test of alpha.test has run") {
      withSeveralModules { build =>
        addLateTestCompilingAfterAlpha1Fast(build)
        val (result, _) = runParsingJsonOutput(build)("alpha.test", "late.test")

        Then("the files Alpha1.fast and Late1.fast exist in <markers>")
        withClue(result.debugString) {
          build.markers should contain allOf ("Alpha1.fast", "Late1.fast")
        }

        And("the test command succeeds")
        shouldSucceed(result)
      }
    }
  }
}
