package org.scalatest.mill

import org.scalatest.matchers.should

enum SampleSuite {
  case Alpha1
  case Alpha2
  case Beta1
}

enum SampleTest(declaration: String) {
  case Fast extends SampleTest("""test("fast", Fast) """)
  case Slow extends SampleTest("""test("slow") """)

  def withEmptyBody: String = declaration + "{}"

  def withBody(body: String): String = declaration + s"{ $body }"

  def sendingWorkingDirectory: String = withBody("""info(System.getProperty("user.dir"))""")

  def creatingFileInWorkingDirectory(fileName: String): String =
    withBody(s"""java.nio.file.Files.write(java.nio.file.Paths.get("$fileName"), Array.emptyByteArray)""")

  def failing: String = withBody("""fail("fails as the scenario asks")""")
}

trait TestCommandArgumentsFixture extends FixtureBuilds { this: should.Matchers =>

  protected def withSuites[A](moduleArguments: String*)(suites: String*)(test: FixtureBuild => A): A =
    withFixtureBuild(FixtureName.arguments) { build =>
      build.addSuites(suites*)
      if (moduleArguments.nonEmpty) {
        build.setModuleArguments(moduleArguments)
      }
      test(build)
    }

  protected def withMarkingSuites[A](test: FixtureBuild => A): A = withMarkingSuites(daemonMode = false)(test)

  protected def withMarkingSuites[A](daemonMode: Boolean)(test: FixtureBuild => A): A =
    withFixtureBuild(FixtureName.classGlobsAndFrameworkArguments, daemonMode) { build =>
      os.makeDir.all(build.markersFolder)
      test(build)
    }

  protected def moduleNode(json: JsonNode): JsonNode = json.node(NodeKind.Module, "core.test")

  protected def suitesRun(json: JsonNode): Seq[String] =
    moduleNode(json).nodes
      .filter(node => node.kind == NodeKind.Suite && node.contents.exists(test => test.kind == NodeKind.Test && !test.outcome.contains("aborted")))
      .map(_.name)
      .sorted

  protected def infoMessages(json: JsonNode): Seq[String] =
    moduleNode(json).nodes.flatMap(_.output).filter(_.kind == OutputItemKind.Info).map(_.message)

  protected def makeAlpha1FastSendInfo(build: FixtureBuild, messageExpression: String): Unit =
    build.replaceInSample(
      s"${SampleSuite.Alpha1}.scala",
      SampleTest.Fast.withEmptyBody,
      s"""override def withFixture(test: NoArgTest): org.scalatest.Outcome = {
         |    if (test.name == "fast") info($messageExpression)
         |    super.withFixture(test)
         |  }
         |
         |  ${SampleTest.Fast.withEmptyBody}""".stripMargin
    )

  protected def runWritingJsonOutput(build: FixtureBuild, arguments: String*): TestCommandResult =
    build.runTestCommandWritingJsonOutputWith(FixtureBuild.jobsArguments(2))(arguments*)

  protected def runReadingJsonOutput(build: FixtureBuild, arguments: String*): (TestCommandResult, JsonNode) =
    build.resultAndJsonOutput(runWritingJsonOutput(build, arguments*))
}
