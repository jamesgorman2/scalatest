package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

final class SeveralModulesSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with JsonOutputFixture {

  private val sampleMarkers: Seq[String] = Seq("Alpha1.fast", "Alpha1.slow", "Alpha2.fast", "Alpha2.slow", "Beta1.fast", "Beta1.slow")

  private def withSeveralModules[A](test: FixtureBuild => A): A =
    withFixtureBuild(FixtureName.severalModules) { build =>
      Given("the JVM test modules alpha.test, holding Alpha1 and Alpha2, and beta.test, holding Beta1")
      And("each suite's tests fast and slow succeed, and each test, when it runs, appends one line to the file <suite>.<test> in <markers>")
      os.makeDir.all(build.markersFolder)
      test(build)
    }

  private def withClassGlobsAcrossModules[A](test: FixtureBuild => A): A =
    withFixtureBuild(FixtureName.classGlobsAcrossModules) { build =>
      Given("the JVM test modules alpha.test, holding Alpha1 and Alpha2, and beta.test, holding Beta1")
      And("each of those suites is an AnyFunSuite with two tests, fast and slow, both of which succeed, and only fast is tagged samples.Fast")
      And("each of those modules holds the object samples.Fast, which is a Tag and not a test class")
      And("<markers> is an absolute path at which an empty folder exists, and each test, when it runs, creates the file <suite>.<test> in <markers>")
      os.makeDir.all(build.markersFolder)
      test(build)
    }

  private def addModulesThatSortByCharacterCode(build: FixtureBuild): Unit = {
    Given("the build also has the JVM test modules B1.test, b10.test and b2.test, each holding one suite whose one test succeeds")
    Seq("B1", "b10", "b2").foreach(module => build.appendToBuild(s"object $module extends SampleModule {\n  object test extends SampleTests\n}"))
  }

  private def addLateTestCompilingAfterAlpha1Fast(build: FixtureBuild): Unit = {
    Given("the build also has the JVM test module late.test, which holds the suite Late1, whose class is samples.Late1")
    And("Late1 is an AnyFunSuite whose test fast creates the file Late1.fast in <markers>")
    And("late.test's compile depends on a task that ends once the file Alpha1.fast exists in <markers>, and fails if that file does not exist within 120 seconds")
    // The wait gates the compile of late, which late.test's compile depends on, so the scenario also fails where the
    // check compiles a module that late.test depends on through moduleDeps.
    build.appendToBuild(
      """object late extends SampleModule {
        |  def alpha1FastExists: Task[Unit] = Task.Anon {
        |    val marker = BuildCtx.workspaceRoot / "markers" / "Alpha1.fast"
        |    val deadline = System.nanoTime() + 120L * 1000000000L
        |    while (!os.exists(marker) && System.nanoTime() < deadline) {
        |      Thread.sleep(100)
        |    }
        |    if (os.exists(marker)) mill.api.Result.Success(()) else mill.api.Result.Failure(s"$marker does not exist after 120 seconds")
        |  }
        |
        |  def compile: T[mill.javalib.api.CompilationResult] = Task {
        |    alpha1FastExists()
        |    super.compile()
        |  }
        |
        |  object test extends SampleTests
        |}""".stripMargin
    )
  }

  private def setForkGroupingOfBetaTest(build: FixtureBuild, forkGrouping: String): Unit =
    build.replaceInBuild(
      "object beta extends SampleModule {\n  object test extends SampleTests\n}",
      s"object beta extends SampleModule {\n  object test extends SampleTests {\n    def testForkGrouping = Task { $forkGrouping }\n  }\n}"
    )

  private def replaceInSuite(build: FixtureBuild, module: String, suite: String)(from: String, to: String): Unit =
    build.replaceInSource(build.workspace / module / "test" / "src" / "samples" / s"$suite.scala", from, to)

  private def runTestCommand(build: FixtureBuild, millFlags: String*)(arguments: String*): TestCommandResult =
    build.runTestCommandWithMillArguments(FixtureBuild.jobsArguments(2) ++ millFlags)(arguments*)

  private def runWritingJsonOutput(build: FixtureBuild, millFlags: String*)(selectors: String*): (TestCommandResult, JsonNode) = {
    When(s"the test command is invoked as mill -j 2 ${millFlags.map(_ + " ").mkString}scalatest/test ${selectors.mkString(" ")} -fJ <report>")
    build.resultAndJsonOutput(runTestCommand(build, millFlags*)((selectors :+ "-fJ" :+ build.jsonOutputFile.toString)*))
  }

  private def runOnBothModulesWritingJsonOutput(build: FixtureBuild, arguments: String*): (TestCommandResult, JsonNode) = {
    When(s"the test command is invoked as mill -j 2 scalatest/test alpha.test beta.test -fJ <report> ${arguments.mkString(" ")}")
    build.resultAndJsonOutput(runTestCommand(build)((Seq("alpha.test", "beta.test", "-fJ", build.jsonOutputFile.toString) ++ arguments)*))
  }

  private def moduleNode(json: JsonNode, module: String): JsonNode = json.node(NodeKind.Module, module)

  private def runNodesOf(json: JsonNode, module: String): Seq[JsonNode] = moduleNode(json, module).nodes.filter(_.kind == NodeKind.Run)

  private def moduleSelectorsOf(json: JsonNode): Seq[(String, Seq[String])] =
    json("moduleSelectors").arr.toSeq.map(entry => entry("selector").str -> entry("modules").arr.toSeq.map(_.str))

  private def moduleNodeNames(json: JsonNode): Seq[String] = json.contents.map(_.name)

  private def markerLines(build: FixtureBuild, marker: String): Seq[String] =
    if (os.exists(build.markersFolder / marker)) os.read.lines(build.markersFolder / marker) else Nil

  private def infoMessagesOfTest(json: JsonNode, suite: String, test: String): Seq[String] =
    json
      .node(NodeKind.Suite, suite)
      .nodes
      .filter(node => node.kind == NodeKind.Test && node.name == test)
      .flatMap(_.output)
      .filter(_.kind == OutputItemKind.Info)
      .map(_.message)

  Feature("Several module selectors") {

    Scenario("Module selectors on either side of an output argument") {
      withSeveralModules { build =>
        When("mill -j 2 scalatest/test alpha.test -fJ <report> beta.test is invoked")
        val result = runTestCommand(build)("alpha.test", "-fJ", build.jsonOutputFile.toString, "beta.test")
        val (_, json) = build.resultAndJsonOutput(result)

        withClue(result.debugString) {
          Then("the JSON output's root holds exactly the module nodes alpha.test and beta.test, in that order")
          moduleNodeNames(json) shouldBe Seq("alpha.test", "beta.test")

          And("the root's moduleSelectors holds exactly the selectors alpha.test and beta.test, in that order")
          moduleSelectorsOf(json).map(_._1) shouldBe Seq("alpha.test", "beta.test")
        }

        And("the test command succeeds")
        shouldSucceed(result)
      }
    }

    Scenario("Only an output argument fails the test command before any test starts") {
      withSeveralModules { build =>
        When("mill -j 2 scalatest/test -fJ <report> is invoked")
        val result = runTestCommand(build)("-fJ", build.jsonOutputFile.toString)

        Then("Mill reports that the test command failed, with a message saying that it was given no module selector")
        shouldFail(result)
        shouldWriteToTheConsole(result, "The test command takes one or more module selectors, but was given none")

        withClue(result.debugString) {
          And("no file exists in <markers>")
          build.markers shouldBe empty

          And("nothing exists at <report>")
          os.exists(build.jsonOutputFile) shouldBe false

          And("Mill's console shows no consolidated output")
          result.treeLines shouldBe empty
          result.summaryLinesAnywhereOnTheConsole shouldBe empty
        }
      }
    }

    val selectorsSelectingBothModules = Seq(
      Seq("alpha.test", "beta.test"),
      Seq("beta.test", "alpha.test"),
      Seq("alpha.test", "__.test"),
      Seq("__.test", "alpha.test", "beta.test")
    )

    for (selectors <- selectorsSelectingBothModules) {
      Scenario(s"Module selectors ${selectors.mkString(" ")} run each test module's tests once, under one module node each") {
        withSeveralModules { build =>
          val (result, json) = runWritingJsonOutput(build)(selectors*)

          withClue(result.debugString) {
            Then("the JSON output's root holds exactly the module nodes alpha.test and beta.test, in that order")
            moduleNodeNames(json) shouldBe Seq("alpha.test", "beta.test")

            And(s"each of the files ${EnglishList.of(sampleMarkers)} in <markers> holds exactly one line")
            sampleMarkers.map(marker => marker -> markerLines(build, marker).size) shouldBe sampleMarkers.map(_ -> 1)
          }

          And("the test command succeeds")
          shouldSucceed(result)
        }
      }
    }

    Scenario("A module selector that resolves to test modules and other modules selects only its test modules") {
      withSeveralModules { build =>
        Given("the build's modules alpha and beta, which hold alpha.test and beta.test, are JVM modules that are not test modules")
        val (result, json) = runWritingJsonOutput(build)("__")

        withClue(result.debugString) {
          Then("the JSON output's root holds exactly the module nodes alpha.test and beta.test, in that order")
          moduleNodeNames(json) shouldBe Seq("alpha.test", "beta.test")

          And("the root's moduleSelectors holds exactly __, resolved to alpha.test and beta.test")
          moduleSelectorsOf(json) shouldBe Seq("__" -> Seq("alpha.test", "beta.test"))
        }

        And("the test command succeeds")
        shouldSucceed(result)
      }
    }

    Scenario("Two module selectors that share a test module each record it") {
      withSeveralModules { build =>
        val (result, json) = runWritingJsonOutput(build)("alpha.test", "__.test")

        Then("the root's moduleSelectors holds alpha.test, resolved to alpha.test, then __.test, resolved to alpha.test and beta.test")
        withClue(result.debugString) {
          moduleSelectorsOf(json) shouldBe Seq("alpha.test" -> Seq("alpha.test"), "__.test" -> Seq("alpha.test", "beta.test"))
        }
      }
    }

    Scenario("A module selector's test modules are ordered by name") {
      withSeveralModules { build =>
        addModulesThatSortByCharacterCode(build)
        val (result, json) = runWritingJsonOutput(build)("__.test")

        Then("the root's moduleSelectors holds exactly __.test, resolved to B1.test, alpha.test, b10.test, b2.test and beta.test, in that order")
        withClue(result.debugString) {
          moduleSelectorsOf(json) shouldBe Seq("__.test" -> Seq("B1.test", "alpha.test", "b10.test", "b2.test", "beta.test"))
        }
      }
    }

    Scenario("Module nodes are ordered by name, whatever the order of the module selectors") {
      withSeveralModules { build =>
        addModulesThatSortByCharacterCode(build)
        val (result, json) = runWritingJsonOutput(build)("b2.test", "beta.test", "b10.test", "alpha.test", "B1.test")

        Then("the JSON output's root holds exactly the module nodes B1.test, alpha.test, b10.test, b2.test and beta.test, in that order")
        withClue(result.debugString) {
          moduleNodeNames(json) shouldBe Seq("B1.test", "alpha.test", "b10.test", "b2.test", "beta.test")
        }
      }
    }

    Scenario("Each module's tests run in the folder sandbox of that module's own module folder") {
      withSeveralModules { build =>
        Given("Alpha1's test fast and Beta1's test fast each send an info message holding the system property user.dir")
        val sendingWorkingDirectory = """test("fast") { info(System.getProperty("user.dir")) }"""
        replaceInSuite(build, "alpha", "Alpha1")("""test("fast") {}""", sendingWorkingDirectory)
        replaceInSuite(build, "beta", "Beta1")("""test("fast") {}""", sendingWorkingDirectory)
        val (result, json) = runWritingJsonOutput(build)("alpha.test", "beta.test")

        withClue(result.debugString) {
          Then("Alpha1's test fast sends the path of the folder sandbox in alpha.test's module folder")
          infoMessagesOfTest(json, "Alpha1", "fast").map(os.Path(_)) shouldBe Seq(build.modulesFolder / "alpha.test" / "sandbox")

          And("Beta1's test fast sends the path of the folder sandbox in beta.test's module folder")
          infoMessagesOfTest(json, "Beta1", "fast").map(os.Path(_)) shouldBe Seq(build.modulesFolder / "beta.test" / "sandbox")
        }
      }
    }
  }

  Feature("Every selected module runs, whatever happens to the others") {

    for (millFlags <- Seq(Nil, Seq("--keep-going"))) {
      val flagsDescription = if (millFlags.isEmpty) "no flags" else millFlags.mkString(" ")

      Scenario(s"A failing test of alpha.test fails the test command and does not stop beta.test's tests, with $flagsDescription") {
        withSeveralModules { build =>
          Given("Alpha1's test slow fails")
          replaceInSuite(build, "alpha", "Alpha1")("""test("slow") {}""", """test("slow") { fail("fails as the scenario asks") }""")
          val (result, _) = runWritingJsonOutput(build, millFlags*)("alpha.test", "beta.test")

          Then("the files Beta1.fast and Beta1.slow exist in <markers>")
          withClue(result.debugString) {
            build.markers should contain allOf ("Beta1.fast", "Beta1.slow")
          }

          And("the test command fails")
          shouldFail(result)
        }
      }
    }

    Scenario("A test module that does not compile fails the test command and does not stop alpha.test's tests") {
      withSeveralModules { build =>
        Given("Beta1's sources do not compile")
        replaceInSuite(build, "beta", "Beta1")("""test("slow") {}""", """test("slow") { notDefinedAnywhere }""")
        val (result, _) = runWritingJsonOutput(build)("alpha.test", "beta.test")

        Then("the files Alpha1.fast, Alpha1.slow, Alpha2.fast and Alpha2.slow exist in <markers>")
        withClue(result.debugString) {
          build.markers should contain allOf ("Alpha1.fast", "Alpha1.slow", "Alpha2.fast", "Alpha2.slow")
        }

        And("Mill reports that the test command failed, naming the failed task beta.test.compile")
        shouldFail(result)
        shouldWriteToTheConsole(result, "org.scalatest.mill.TestCommandModule/test beta.test.compile")
      }
    }

    Scenario("Two of beta.test's fork groups each hold only samples.Beta1") {
      withSeveralModules { build =>
        Given("beta.test's testForkGrouping gives two fork groups, each holding only samples.Beta1")
        setForkGroupingOfBetaTest(build, """Seq(Seq("samples.Beta1"), Seq("samples.Beta1"))""")
        val (result, json) = runWritingJsonOutput(build)("alpha.test", "beta.test")

        withClue(result.debugString) {
          Then("the files Alpha1.fast and Alpha1.slow exist in <markers>, and neither Beta1.fast nor Beta1.slow does")
          build.markers should contain allOf ("Alpha1.fast", "Alpha1.slow")
          build.markers should contain noneOf ("Beta1.fast", "Beta1.slow")

          And("the module node beta.test holds no run node, its outcome is aborted, and its failures holds exactly one entry")
          val beta = moduleNode(json, "beta.test")
          runNodesOf(json, "beta.test") shouldBe empty
          beta.outcome shouldBe Some("aborted")
          beta.failureMessages should have size 1

          And("that entry's message contains samples.Beta1 and does not contain beta.test")
          beta.failureMessages.head should include("samples.Beta1")
          beta.failureMessages.head should not include "beta.test"

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
        val (result, _) = runWritingJsonOutput(build)("alpha.test", "late.test")

        Then("the files Alpha1.fast and Late1.fast exist in <markers>")
        withClue(result.debugString) {
          build.markers should contain allOf ("Alpha1.fast", "Late1.fast")
        }

        And("the test command succeeds")
        shouldSucceed(result)
      }
    }
  }

  Feature("Class globs and framework arguments across modules") {

    val argumentsAndMarkers = Seq(
      Seq("--", "samples.Alpha1", "samples.Beta1") -> Seq("Alpha1.fast", "Alpha1.slow", "Beta1.fast", "Beta1.slow"),
      Seq("--", "--", "-n", "samples.Fast") -> Seq("Alpha1.fast", "Alpha2.fast", "Beta1.fast"),
      Seq("--", "*1", "--", "-z", "slow") -> Seq("Alpha1.slow", "Beta1.slow")
    )

    for ((arguments, markers) <- argumentsAndMarkers) {
      Scenario(s"Arguments ${arguments.mkString(" ")}") {
        withClassGlobsAcrossModules { build =>
          val (result, _) = runOnBothModulesWritingJsonOutput(build, arguments*)

          Then(s"the files in <markers> are exactly ${EnglishList.of(markers)}")
          withClue(result.debugString) {
            build.markers shouldBe markers
          }

          And("the test command succeeds")
          shouldSucceed(result)
        }
      }
    }

    Scenario("Class globs that match only alpha.test's classes") {
      withClassGlobsAcrossModules { build =>
        val (result, json) = runOnBothModulesWritingJsonOutput(build, "--", "samples.Alpha*")

        withClue(result.debugString) {
          Then("the files in <markers> are exactly Alpha1.fast, Alpha1.slow, Alpha2.fast and Alpha2.slow")
          build.markers shouldBe Seq("Alpha1.fast", "Alpha1.slow", "Alpha2.fast", "Alpha2.slow")

          And("the module node beta.test holds no run node, its outcome is aborted, and its failures holds exactly one entry")
          val beta = moduleNode(json, "beta.test")
          runNodesOf(json, "beta.test") shouldBe empty
          beta.outcome shouldBe Some("aborted")
          beta.failureMessages should have size 1

          And("that entry's message is Test selector does not match any test: samples.Alpha* and Run discoveredTestClasses to see available tests, joined by a line feed")
          beta.failureMessages.head shouldBe noTestMatchedMessageNaming("samples.Alpha*")

          And("the module node alpha.test has no outcome and no failures")
          shouldHaveNoKeys(moduleNode(json, "alpha.test"), "outcome", "failures")

          And("the JSON output's root has no outcome and no failures")
          shouldHaveNoKeys(json, "outcome", "failures")
        }

        And("the test command fails")
        shouldFail(result)
      }
    }

    Scenario("Framework arguments that exclude every test the class globs keep") {
      withClassGlobsAcrossModules { build =>
        val (result, json) = runOnBothModulesWritingJsonOutput(build, "--", "*1", "--", "-z", "nothing-matches")

        withClue(result.debugString) {
          Then("no file exists in <markers>")
          build.markers shouldBe empty

          for (module <- Seq("alpha.test", "beta.test")) {
            And(s"the module node $module holds one or more run nodes, and has the outcome aborted and a failures that holds exactly one entry")
            runNodesOf(json, module) should not be empty
            moduleNode(json, module).outcome shouldBe Some("aborted")
            moduleNode(json, module).failureMessages should have size 1
          }

          And("each of those entries' message is Test selector does not match any test: *1 and Run discoveredTestClasses to see available tests, joined by a line feed")
          Seq("alpha.test", "beta.test").map(module => moduleNode(json, module).failureMessages.head) shouldBe
            Seq.fill(2)(noTestMatchedMessageNaming("*1"))

          And("the JSON output's root has no outcome and no failures")
          shouldHaveNoKeys(json, "outcome", "failures")
        }

        And("the test command fails")
        shouldFail(result)
      }
    }
  }
}
