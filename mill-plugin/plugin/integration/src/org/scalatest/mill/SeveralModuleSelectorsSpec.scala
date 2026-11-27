package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should
import org.scalatest.mill.tags.AreaTag
import org.scalatest.mill.tags.CoreTag

final class SeveralModuleSelectorsSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with SeveralModulesFixture {

  protected def givenTheBackground(): Unit = {
    givenABuildThatAddsTheMillPlugin()
    andAlphaTestAndBetaTestUseThatRelease()
    And("each of those modules has testParallelism off and testSandboxWorkingDir on, and leaves testForkGrouping, testBatchFrameworkTasks, testArgsDefault and testReportXml unset")
    andAlphaTestHoldsAlpha1AndAlpha2AndBetaTestHoldsBeta1()
    And("each of those suites is an AnyFunSuite with two tests, fast and slow, both of which succeed")
    And("<markers> is an absolute path at which an empty folder exists, and each test, when it runs, appends one line to the file <suite>.<test> in <markers>")
    And("the test command is invoked as mill -j 2 scalatest/test <selectors> -fJ <report> unless the scenario states its own mill command line")
    andEachArgumentReachesMillAsWritten()
    andNothingExistsAtTheReport()
    andTheJsonOutputIsParsedFromTheReport()
  }

  private val sharedUnchangedSeveralModules: SharedFixtureBuild = sharedSeveralModules(_ => ())

  private val sharedSeveralModulesWithModulesThatSortByCharacterCode: SharedFixtureBuild =
    sharedSeveralModules { build =>
      Seq("B1", "b10", "b2").foreach(module => build.appendToBuild(moduleWithEmptyTestModule(module)))
    }

  private def inSharedSeveralModulesWithModulesThatSortByCharacterCode[A](test: FixtureBuild => A): A =
    inSharedBuild(sharedSeveralModulesWithModulesThatSortByCharacterCode) { build =>
      Given("the build also has the JVM test modules B1.test, b10.test and b2.test, each testing with com.edc::scalatest at the release under test and holding one AnyFunSuite whose one test succeeds")
      test(build)
    }

  private val alphaAndEveryTestModule: Seq[String] = Seq("alpha.test", "__.test")

  private final case class ModuleSelectorEntry(selector: String, modules: Seq[String])

  private final case class RunAndMarkerLineCounts(result: TestCommandResult, json: JsonNode, markerLineCounts: Seq[(SampleMarker, Int)])

  private lazy val sharedRunOnAlphaAndEveryTestModule: RunAndMarkerLineCounts =
    sharedUnchangedSeveralModules.use { build =>
      val (result, json) = runTestCommandWithTwoJobsParsingJsonOutput(build)(alphaAndEveryTestModule*)
      RunAndMarkerLineCounts(result, json, SampleMarker.all.map(marker => marker -> markerLines(build, marker).size))
    }

  private def givenWhenSharedRunOnAlphaAndEveryTestModule(): RunAndMarkerLineCounts = {
    givenTheBackground()
    whenTheTestCommandIsInvokedWith(theModuleSelectors(alphaAndEveryTestModule))
    sharedRunOnAlphaAndEveryTestModule
  }

  private def whenTheTestCommandIsInvokedWithTwoJobsParsingJsonOutput(build: FixtureBuild)(selectors: String*): (TestCommandResult, JsonNode) =
    build.resultAndJsonOutput(whenTheTestCommandIsInvokedWithTwoJobsWritingJsonOutput(build, selectors*))

  private def moduleSelectorsOf(json: JsonNode): Seq[ModuleSelectorEntry] =
    json("moduleSelectors").arr.toSeq.map(entry => ModuleSelectorEntry(entry("selector").str, entry("modules").arr.toSeq.map(_.str)))

  private def markerLines(build: FixtureBuild, marker: SampleMarker): Seq[String] =
    if (os.exists(build.markersFolder / marker.fileName)) os.read.lines(build.markersFolder / marker.fileName) else Nil

  private def infoMessagesOfTest(json: JsonNode, suite: String, test: String): Seq[String] =
    json
      .node(NodeKind.Suite, suite)
      .nodes
      .filter(node => node.kind == NodeKind.Test && node.name == test)
      .flatMap(_.output)
      .filter(_.kind == OutputItemKind.Info)
      .map(_.message)

  Feature("Several module selectors") {

    Scenario("Module selectors on either side of an output argument", AreaTag.Modules) {
      inSharedBuild(sharedUnchangedSeveralModules) { build =>
        When("mill -j 2 scalatest/test alpha.test -fJ <report> beta.test is invoked")
        val result = runTestCommandWithTwoJobs(build)("alpha.test", "-fJ", build.jsonOutputFile.toString, "beta.test")
        val (_, json) = build.resultAndJsonOutput(result)

        withClue(result.debugString) {
          Then("the JSON output's root holds exactly the module nodes alpha.test and beta.test, in that order")
          moduleNodeNames(json) shouldBe Seq("alpha.test", "beta.test")

          And("the JSON output's root moduleSelectors holds exactly two entries, whose selector values are alpha.test and beta.test, in that order")
          moduleSelectorsOf(json).map(_.selector) shouldBe Seq("alpha.test", "beta.test")
        }

        And("the test command succeeds")
        shouldSucceed(result)
      }
    }

    Scenario("Only an output argument", AreaTag.Modules) {
      inSharedBuild(sharedUnchangedSeveralModules) { build =>
        When("mill -j 2 scalatest/test -fJ <report> is invoked")
        val result = runTestCommandWithTwoJobs(build)("-fJ", build.jsonOutputFile.toString)

        Then("Mill reports that the test command failed, with a message saying that the test command was given no module selector")
        shouldFail(result)
        shouldWriteToTheConsole(result, "The test command takes one or more module selectors, but was given none")

        shouldStartNoTestAndWriteNoConsolidatedOutput(build, result)
      }
    }

    Scenario(s"Module selectors ${alphaAndEveryTestModule.mkString(" ")}", AreaTag.Modules, CoreTag) {
      val run = givenWhenSharedRunOnAlphaAndEveryTestModule()

      withClue(run.result.debugString) {
        Then("the JSON output's root holds exactly the module nodes alpha.test and beta.test, in that order")
        moduleNodeNames(run.json) shouldBe Seq("alpha.test", "beta.test")

        And(s"each of the files ${EnglishList.of(SampleMarker.all.map(_.fileName))} in <markers> holds exactly one line")
        run.markerLineCounts shouldBe SampleMarker.all.map(_ -> 1)
      }

      And("the test command succeeds")
      shouldSucceed(run.result)
    }

    Scenario("A module selector that resolves to test modules and other modules", AreaTag.Modules) {
      inSharedBuild(sharedUnchangedSeveralModules) { build =>
        Given("the build's modules alpha and beta, which hold alpha.test and beta.test, are JVM modules that are not test modules")
        val (result, json) = whenTheTestCommandIsInvokedWithTwoJobsParsingJsonOutput(build)("__")

        withClue(result.debugString) {
          Then("the JSON output's root holds exactly the module nodes alpha.test and beta.test, in that order")
          moduleNodeNames(json) shouldBe Seq("alpha.test", "beta.test")

          And("the JSON output's root moduleSelectors holds exactly one entry, whose selector is __ and whose modules holds exactly alpha.test and beta.test")
          moduleSelectorsOf(json) shouldBe Seq(ModuleSelectorEntry("__", Seq("alpha.test", "beta.test")))
        }

        And("the test command succeeds")
        shouldSucceed(result)
      }
    }

    Scenario("Two module selectors that share a test module", AreaTag.Modules) {
      val run = givenWhenSharedRunOnAlphaAndEveryTestModule()
      val moduleSelectors = moduleSelectorsOf(run.json)

      withClue(run.result.debugString) {
        Then("the JSON output's root moduleSelectors holds exactly two entries")
        moduleSelectors should have size 2

        And("the first entry's selector is alpha.test and its modules holds exactly alpha.test")
        moduleSelectors.head shouldBe ModuleSelectorEntry("alpha.test", Seq("alpha.test"))

        And("the second entry's selector is __.test and its modules holds exactly alpha.test and beta.test, in that order")
        moduleSelectors(1) shouldBe ModuleSelectorEntry("__.test", Seq("alpha.test", "beta.test"))
      }
    }

    Scenario("A module selector's test modules are ordered by name", AreaTag.Modules) {
      inSharedSeveralModulesWithModulesThatSortByCharacterCode { build =>
        val (result, json) = whenTheTestCommandIsInvokedWithTwoJobsParsingJsonOutput(build)("__.test")

        val moduleSelectors = moduleSelectorsOf(json)

        withClue(result.debugString) {
          Then("the JSON output's root moduleSelectors holds exactly one entry, whose selector is __.test")
          moduleSelectors.map(_.selector) shouldBe Seq("__.test")

          And("that entry's modules holds exactly B1.test, alpha.test, b10.test, b2.test and beta.test, in that order")
          moduleSelectors.head.modules shouldBe Seq("B1.test", "alpha.test", "b10.test", "b2.test", "beta.test")
        }
      }
    }

    Scenario("Module names that sort by character code", AreaTag.Modules) {
      inSharedSeveralModulesWithModulesThatSortByCharacterCode { build =>
        val (result, json) = whenTheTestCommandIsInvokedWithTwoJobsParsingJsonOutput(build)("b2.test", "beta.test", "b10.test", "alpha.test", "B1.test")

        Then("the JSON output's root holds exactly the module nodes B1.test, alpha.test, b10.test, b2.test and beta.test, in that order")
        withClue(result.debugString) {
          moduleNodeNames(json) shouldBe Seq("B1.test", "alpha.test", "b10.test", "b2.test", "beta.test")
        }
      }
    }

    Scenario("The working directory of each module's tests", AreaTag.Workers) {
      withSeveralModules { build =>
        Given("Alpha1's test fast and Beta1's test fast each send an info message holding the system property user.dir")
        val sendingWorkingDirectory = """test("fast") { info(System.getProperty("user.dir")) }"""
        replaceInSuite(build, "alpha", "Alpha1")("""test("fast") {}""", sendingWorkingDirectory)
        replaceInSuite(build, "beta", "Beta1")("""test("fast") {}""", sendingWorkingDirectory)
        val (result, json) = whenTheTestCommandIsInvokedWithTwoJobsParsingJsonOutput(build)("alpha.test", "beta.test")

        withClue(result.debugString) {
          Then("in the JSON output, the output of Alpha1's test fast holds an info message whose message is the path of the folder sandbox in out/org.scalatest.mill.TestCommandModule/modules/alpha.test in the build's workspace")
          infoMessagesOfTest(json, "Alpha1", "fast").map(os.Path(_)) shouldBe Seq(build.modulesFolder / "alpha.test" / "sandbox")

          And("in the JSON output, the output of Beta1's test fast holds an info message whose message is the path of the folder sandbox in out/org.scalatest.mill.TestCommandModule/modules/beta.test in the build's workspace")
          infoMessagesOfTest(json, "Beta1", "fast").map(os.Path(_)) shouldBe Seq(build.modulesFolder / "beta.test" / "sandbox")
        }
      }
    }
  }
}
