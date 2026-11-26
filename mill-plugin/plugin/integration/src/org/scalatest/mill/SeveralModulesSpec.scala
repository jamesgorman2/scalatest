package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

final class SeveralModulesSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with FixtureBuilds {

  private val sampleMarkers: Seq[String] = Seq("Alpha1.fast", "Alpha1.slow", "Alpha2.fast", "Alpha2.slow", "Beta1.fast", "Beta1.slow")

  private def withSeveralModules[A](test: FixtureBuild => A): A =
    withFixtureBuild(FixtureName.severalModules) { build =>
      Given("the JVM test modules alpha.test, holding Alpha1 and Alpha2, and beta.test, holding Beta1")
      And("each suite's tests fast and slow succeed, each appending one line to a file in <markers> named after it as it runs")
      os.makeDir.all(build.markersFolder)
      test(build)
    }

  private def addModulesThatSortByCharacterCode(build: FixtureBuild): Unit = {
    Given("the build also has the JVM test modules B1.test, b10.test and b2.test, each holding one suite whose one test succeeds")
    Seq("B1", "b10", "b2").foreach(module => build.appendToBuild(s"object $module extends SampleModule"))
  }

  private def replaceInSuite(build: FixtureBuild, module: String, suite: String)(from: String, to: String): Unit = {
    val source = build.workspace / module / "test" / "src" / "samples" / s"$suite.scala"
    val text = os.read(source)
    withClue(s"$suite.scala holds no $from:\n$text\n") {
      text should include(from)
    }
    os.write.over(source, text.replace(from, to))
  }

  private def runTestCommand(build: FixtureBuild, millFlags: String*)(arguments: String*): TestCommandResult =
    build.runTestCommandWithMillArguments(FixtureBuild.jobsArguments(2) ++ millFlags)(arguments*)

  private def runWritingJsonOutput(build: FixtureBuild, millFlags: String*)(selectors: String*): (TestCommandResult, JsonNode) = {
    When(s"the test command is invoked as mill -j 2 ${millFlags.map(_ + " ").mkString}scalatest/test ${selectors.mkString(" ")} -fJ <report>")
    build.resultAndJsonOutput(runTestCommand(build, millFlags*)((selectors :+ "-fJ" :+ build.jsonOutputFile.toString)*))
  }

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
          os.list(build.markersFolder) shouldBe empty

          And("nothing exists at <report>")
          os.exists(build.jsonOutputFile) shouldBe false

          And("Mill's console shows no consolidated output")
          result.treeLines shouldBe empty
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
            Seq("Beta1.fast", "Beta1.slow").filterNot(marker => os.exists(build.markersFolder / marker)) shouldBe empty
          }

          And("the test command fails")
          shouldFail(result)
        }
      }
    }
  }
}
