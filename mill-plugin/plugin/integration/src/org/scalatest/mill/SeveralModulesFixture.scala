package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should

trait SeveralModulesFixture extends JsonOutputFixture { this: should.Matchers & GivenWhenThen =>

  protected def withSeveralModules[A](test: FixtureBuild => A): A =
    withFixtureBuild(FixtureName.severalModules) { build =>
      Given("the JVM test modules alpha.test, holding Alpha1 and Alpha2, and beta.test, holding Beta1")
      And("each suite's tests fast and slow succeed, and each test, when it runs, appends one line to the file <suite>.<test> in <markers>")
      os.makeDir.all(build.markersFolder)
      test(build)
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
    When(s"the test command is invoked as mill -j 2 ${millFlags.map(_ + " ").mkString}scalatest/test ${selectors.mkString(" ")} -fJ <report>")
    build.resultAndJsonOutput(runTestCommand(build, millFlags*)((selectors :+ "-fJ" :+ build.jsonOutputFile.toString)*))
  }

  protected def moduleNode(json: JsonNode, module: String): JsonNode = json.node(NodeKind.Module, module)

  protected def runNodesOf(json: JsonNode, module: String): Seq[JsonNode] = moduleNode(json, module).nodes.filter(_.kind == NodeKind.Run)

  protected def moduleNodeNames(json: JsonNode): Seq[String] = json.contents.map(_.name)
}
