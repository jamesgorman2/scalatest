package org.scalatest.mill

import mill.testkit.IntegrationTester
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should

trait FixtureBuilds { this: should.Matchers =>

  protected def withFixtureBuild[A](fixture: FixtureName, daemonMode: Boolean = false)(test: FixtureBuild => A): A = {
    val tester = integrationTester(fixture, daemonMode)
    try {
      test(new FixtureBuild(tester))
    } finally {
      tester.close()
    }
  }

  protected def integrationTester(fixture: FixtureName, daemonMode: Boolean): IntegrationTester =
    IntegrationTester(
      daemonMode = daemonMode,
      workspaceSourcePath = IntegrationEnvironment.fixture(fixture),
      millExecutable = IntegrationEnvironment.millExecutable
    )

  protected def theModuleSelectors(selectors: Seq[String]): String =
    selectors match {
      case Seq(selector) => s"the module selector $selector"
      case _ => s"the module selectors ${selectors.mkString(" ")}"
    }

  protected def runTestCommandWithTwoJobs(build: FixtureBuild, millFlags: String*)(arguments: String*): TestCommandResult =
    build.runTestCommandWithMillArguments(FixtureBuild.jobsArguments(2) ++ millFlags)(arguments*)

  protected def runTestCommandWithTwoJobsWritingJsonOutput(build: FixtureBuild, millFlags: String*)(selectors: String*): TestCommandResult =
    runTestCommandWithTwoJobs(build, millFlags*)((selectors :+ "-fJ" :+ build.jsonOutputFile.toString)*)

  protected def moduleWithTestModuleHolding(module: String, members: String*): String = moduleWithTestModule(module, "SampleTests", members)

  protected def moduleWithTestModule(module: String, testModuleParents: String, members: Seq[String]): String =
    Seq(
      s"object $module extends SampleModule {",
      s"  object test extends $testModuleParents {",
      indented(2)(members.mkString("\n\n")),
      "  }",
      "}"
    ).mkString("\n")

  protected def indented(levels: Int)(code: String): String =
    code.linesIterator.map(line => if (line.isEmpty) line else "  " * levels + line).mkString("\n")

  protected def shouldSucceed(result: TestCommandResult): Unit =
    withClue(result.debugString) {
      result.succeeded shouldBe true
    }

  protected def shouldFail(result: TestCommandResult): Unit =
    withClue(result.debugString) {
      result.succeeded shouldBe false
    }

  protected def shouldFailBeforeAnyTestStarts(build: FixtureBuild, result: TestCommandResult): Unit =
    withClue(result.debugString) {
      result.succeeded shouldBe false
      build.anyTestWorkerStarted shouldBe false
    }

  protected def shouldWriteToTheConsole(result: TestCommandResult, text: String): Unit =
    withClue(result.debugString) {
      result.consoleText should include(text)
    }

  protected def noTestMatchedMessageNaming(classGlobs: String): String =
    s"Test selector does not match any test: $classGlobs\nRun discoveredTestClasses to see available tests"
}

trait FixtureBuildSteps extends FixtureBuilds { this: should.Matchers & GivenWhenThen =>

  protected def andAnEarlierTestCommandWroteTheReport(): Unit =
    And("<report> is an absolute path at which a file exists that an earlier test command wrote")

  protected def whenTheTestCommandIsInvokedWith(arguments: String): Unit =
    When(s"the test command is invoked with $arguments")

  protected def whenTheTestCommandIsInvokedWithTwoJobsWritingJsonOutput(build: FixtureBuild, selectors: String*): TestCommandResult = {
    whenTheTestCommandIsInvokedWith(theModuleSelectors(selectors))
    runTestCommandWithTwoJobsWritingJsonOutput(build)(selectors*)
  }

  protected def andNoFileShouldExistInTheMarkers(build: FixtureBuild): Unit = {
    And("no file exists in <markers>")
    build.markers shouldBe empty
  }

  protected def andTheConsoleShouldShowNoConsolidatedOutput(result: TestCommandResult): Unit = {
    And("Mill's console shows no consolidated output")
    result.treeLines shouldBe empty
    result.summaryLinesAnywhereOnTheConsole shouldBe empty
  }

  protected def shouldStartNoTestAndWriteNoConsolidatedOutput(build: FixtureBuild, result: TestCommandResult): Unit =
    withClue(result.debugString) {
      andNoFileShouldExistInTheMarkers(build)

      And("nothing exists at <report>")
      os.exists(build.jsonOutputFile) shouldBe false

      andTheConsoleShouldShowNoConsolidatedOutput(result)
    }
}
