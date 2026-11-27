package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.Suite
import org.scalatest.matchers.should

trait SeveralModulesFixture extends JsonOutputFixture with SharedFixtureBuilds { this: Suite & should.Matchers & GivenWhenThen =>

  protected def givenTheBackground(): Unit

  protected def withSeveralModules[A](test: FixtureBuild => A): A =
    withFixtureBuild(FixtureName.severalModules) { build =>
      givenTheBackground()
      os.makeDir.all(build.markersFolder)
      test(build)
    }

  protected def sharedSeveralModules(setUp: FixtureBuild => Unit): SharedFixtureBuild =
    sharedFixtureBuild(FixtureName.severalModules, WorkspaceReset.emptyMarkersFolder.and(WorkspaceReset.noJsonOutputFile))(setUp)

  protected def inSharedBuild[A](sharedBuild: SharedFixtureBuild)(test: FixtureBuild => A): A = {
    givenTheBackground()
    sharedBuild.use(test)
  }

  protected def givenABuildThatAddsTheMillPlugin(): Unit =
    Given("a Mill 1.3.1 build that adds the Mill plugin, at the release of this fork under test, as the entry com.edc::scalatest-mill of its build.mill header's mvnDeps")

  protected def andAlphaTestAndBetaTestUseThatRelease(): Unit =
    And("the build's JVM test modules alpha.test and beta.test each test with com.edc::scalatest at that release, and their definitions name nothing of the plugin")

  protected def andAlphaTestHoldsAlpha1AndAlpha2AndBetaTestHoldsBeta1(): Unit =
    And("alpha.test holds the suites Alpha1 and Alpha2, whose classes are samples.Alpha1 and samples.Alpha2, and beta.test holds the suite Beta1, whose class is samples.Beta1")

  protected def andEachArgumentReachesMillAsWritten(): Unit =
    And("each argument of a mill command line reaches mill exactly as written, with no shell expansion")

  protected def andTheJsonOutputIsReadFromTheReport(): Unit = {
    And("<report> is an absolute path at which nothing exists")
    And("the JSON output is the JSON value parsed from the file at <report> once the test command has ended")
  }

  protected def addToBetaTest(build: FixtureBuild, member: String): Unit =
    addToTestModule(build, "beta", member)

  protected def addToTestModule(build: FixtureBuild, module: String, member: String): Unit =
    build.replaceInBuild(
      s"object $module extends SampleModule {\n  object test extends SampleTests\n}",
      s"object $module extends SampleModule {\n  object test extends SampleTests {\n${member.linesIterator.map("    " + _).mkString("\n")}\n  }\n}"
    )

  protected def replaceInSuite(build: FixtureBuild, module: String, suite: String)(from: String, to: String): Unit =
    build.replaceInSource(build.workspace / module / "test" / "src" / "samples" / s"$suite.scala", from, to)

  protected def runTestCommand(build: FixtureBuild, millFlags: String*)(arguments: String*): TestCommandResult =
    build.runTestCommandWithMillArguments(FixtureBuild.jobsArguments(2) ++ millFlags)(arguments*)

  protected def runParsingJsonOutput(build: FixtureBuild, millFlags: String*)(selectors: String*): (TestCommandResult, JsonNode) = {
    whenTheTestCommandIsInvokedWritingJsonOutput(millFlags*)(selectors*)
    resultAndJsonOutputOfTestCommand(build, millFlags*)(selectors*)
  }

  protected def whenTheTestCommandIsInvokedWritingJsonOutput(millFlags: String*)(selectors: String*): Unit =
    When(s"the test command is invoked as mill -j 2 ${millFlags.map(_ + " ").mkString}scalatest/test ${selectors.mkString(" ")} -fJ <report>")

  protected def resultAndJsonOutputOfTestCommand(build: FixtureBuild, millFlags: String*)(selectors: String*): (TestCommandResult, JsonNode) =
    build.resultAndJsonOutput(runTestCommand(build, millFlags*)((selectors :+ "-fJ" :+ build.jsonOutputFile.toString)*))

  protected def moduleNode(json: JsonNode, module: String): JsonNode = json.node(NodeKind.Module, module)

  protected def runNodesOf(json: JsonNode, module: String): Seq[JsonNode] = moduleNode(json, module).nodes.filter(_.kind == NodeKind.Run)

  protected def moduleNodeNames(json: JsonNode): Seq[String] = json.contents.map(_.name)
}
