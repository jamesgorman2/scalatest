package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.Suite
import org.scalatest.matchers.should

trait SeveralModulesFixture extends JsonOutputFixture with SharedFixtureBuilds with FixtureBuildSteps { this: Suite & should.Matchers & GivenWhenThen =>

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

  protected def andAlphaTestAndBetaTestUseThatRelease(): Unit =
    And("the build's JVM test modules alpha.test and beta.test each test with com.edc::scalatest at that release, and their definitions name nothing of the plugin")

  protected def andAlphaTestHoldsAlpha1AndAlpha2AndBetaTestHoldsBeta1(): Unit =
    And("alpha.test holds the suites Alpha1 and Alpha2, whose classes are samples.Alpha1 and samples.Alpha2, and beta.test holds the suite Beta1, whose class is samples.Beta1")

  protected def andEachArgumentReachesMillAsWritten(): Unit =
    And("each argument of a mill command line reaches mill exactly as written, with no shell expansion")

  protected def andNothingExistsAtTheReport(): Unit =
    And("<report> is an absolute path at which nothing exists")

  protected def andTheJsonOutputIsParsedFromTheReport(): Unit =
    And("the JSON output is the JSON value parsed from the file at <report> once the test command has ended")

  protected def addToTestModule(build: FixtureBuild, module: String, members: String*): Unit =
    build.replaceInBuild(moduleWithEmptyTestModule(module), moduleWithTestModuleHolding(module, members*))

  protected def moduleWithEmptyTestModule(module: String): String =
    Seq(
      s"object $module extends SampleModule {",
      "  object test extends SampleTests",
      "}"
    ).mkString("\n")

  protected def replaceInSuite(build: FixtureBuild, module: String, suite: String)(from: String, to: String): Unit =
    build.replaceInSource(build.workspace / module / "test" / "src" / "samples" / s"$suite.scala", from, to)

  protected def runTestCommandWithTwoJobsParsingJsonOutput(build: FixtureBuild, millFlags: String*)(selectors: String*): (TestCommandResult, JsonNode) =
    build.resultAndJsonOutput(runTestCommandWithTwoJobsWritingJsonOutput(build, millFlags*)(selectors*))

  protected def moduleNode(json: JsonNode, module: String): JsonNode = json.node(NodeKind.Module, module)

  protected def runNodesOf(json: JsonNode, module: String): Seq[JsonNode] = moduleNode(json, module).nodes.filter(_.kind == NodeKind.Run)

  protected def moduleNodeNames(json: JsonNode): Seq[String] = json.contents.map(_.name)

  protected def shouldBeAbortedWithOneFailure(json: JsonNode, module: String): Unit = {
    val node = moduleNode(json, module)
    node.outcome shouldBe Some("aborted")
    node.failureMessages should have size 1
  }

  protected def shouldBeAbortedWithOneFailureContaining(json: JsonNode, module: String, text: String): Unit = {
    shouldBeAbortedWithOneFailure(json, module)
    moduleNode(json, module).failureMessages.head should include(text)
  }
}
