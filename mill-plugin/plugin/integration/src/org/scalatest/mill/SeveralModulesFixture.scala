package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.Suite
import org.scalatest.matchers.should

trait SeveralModulesFixture extends JsonOutputFixture with SharedSandboxes { this: Suite & should.Matchers & GivenWhenThen =>

  protected def withSeveralModules[A](test: FixtureBuild => A): A =
    withFixtureBuild(FixtureName.severalModules) { build =>
      givenTheSeveralModules()
      os.makeDir.all(build.markersFolder)
      test(build)
    }

  protected def sharedSeveralModules(setUp: FixtureBuild => Unit): SharedSandbox =
    sharedSandbox(FixtureName.severalModules, SandboxReset.emptyMarkersFolder.and(SandboxReset.noJsonOutputFile))(setUp)

  protected def inSharedSeveralModules[A](sandbox: SharedSandbox)(test: FixtureBuild => A): A = {
    givenTheSeveralModules()
    sandbox.use(test)
  }

  protected def givenTheSeveralModules(): Unit = {
    Given("the JVM test modules alpha.test, holding Alpha1 and Alpha2, and beta.test, holding Beta1")
    And("each suite's tests fast and slow succeed, and each test, when it runs, appends one line to the file <suite>.<test> in <markers>")
  }

  protected def addToBetaTest(build: FixtureBuild, member: String): Unit =
    build.replaceInBuild(
      "object beta extends SampleModule {\n  object test extends SampleTests\n}",
      s"object beta extends SampleModule {\n  object test extends SampleTests {\n    $member\n  }\n}"
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
