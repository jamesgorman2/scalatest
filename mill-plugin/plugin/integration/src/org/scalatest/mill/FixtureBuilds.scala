package org.scalatest.mill

import mill.testkit.IntegrationTester
import org.scalatest.matchers.should

trait FixtureBuilds { this: should.Matchers =>

  protected def withFixtureBuild[A](fixture: FixtureName, daemonMode: Boolean = false)(test: FixtureBuild => A): A = {
    val tester = IntegrationTester(
      daemonMode = daemonMode,
      workspaceSourcePath = IntegrationEnvironment.fixture(fixture),
      millExecutable = IntegrationEnvironment.millExecutable
    )
    try {
      test(new FixtureBuild(tester))
    } finally {
      tester.close()
    }
  }

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
}
