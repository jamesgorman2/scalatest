package org.scalatest.mill

import org.scalatest.Suite
import org.scalatest.matchers.should

trait SingleModuleFixture extends SharedFixtureBuilds { this: Suite & should.Matchers =>

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

  protected def sharedMarkingSuites(setUp: FixtureBuild => Unit): SharedFixtureBuild =
    sharedFixtureBuild(FixtureName.classGlobsAndFrameworkArguments, WorkspaceReset.noModuleFolders) { build =>
      os.makeDir.all(build.markersFolder)
      setUp(build)
    }

  protected def moduleNode(json: JsonNode): JsonNode = json.node(NodeKind.Module, "core.test")

  protected def infoMessages(json: JsonNode): Seq[String] = moduleNode(json).infoMessagesInSubtree

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
