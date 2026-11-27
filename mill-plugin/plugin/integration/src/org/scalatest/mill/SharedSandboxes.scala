package org.scalatest.mill

import mill.testkit.IntegrationTester
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Suite
import org.scalatest.matchers.should

import scala.collection.mutable

trait SharedSandboxes extends FixtureBuilds with BeforeAndAfterAll { this: Suite & should.Matchers =>

  private val openTesters: mutable.Buffer[IntegrationTester] = mutable.Buffer.empty

  private val savedEarlierReport: os.SubPath = os.sub / "earlier-report.json"

  // A shared sandbox always runs Mill without a daemon. A daemon would keep the environment it started in, and its
  // caches, from one scenario to the next, and scenarios test the difference between running with and without one.
  protected final class SharedSandbox(fixture: FixtureName, reset: SandboxReset, setUp: FixtureBuild => Unit) {

    private lazy val build: FixtureBuild = {
      val tester = integrationTester(fixture, daemonMode = false)
      openTesters += tester
      val build = new FixtureBuild(tester)
      setUp(build)
      build
    }

    // Every scenario reads the build file, so every reset restores it.
    private lazy val resetToTheSetUp: SandboxReset = reset.and(SandboxReset.buildFileHolding(build.buildText))

    def use[A](test: FixtureBuild => A): A = {
      resetToTheSetUp.restore(build)
      test(build)
    }
  }

  protected def sharedSandbox(fixture: FixtureName, reset: SandboxReset)(setUp: FixtureBuild => Unit): SharedSandbox =
    new SharedSandbox(fixture, reset, setUp)

  protected def sharedSandboxWithReportFromAnEarlierTestCommand(fixture: FixtureName)(earlierTestCommand: FixtureBuild => TestCommandResult): SharedSandbox =
    sharedSandbox(fixture, SandboxReset.emptyMarkersFolder.and(SandboxReset.jsonOutputFileAsSavedIn(savedEarlierReport))) { build =>
      writeReportFromAnEarlierTestCommand(build)(earlierTestCommand)
      os.copy(build.jsonOutputFile, build.workspace / savedEarlierReport)
    }

  protected def writeReportFromAnEarlierTestCommand(build: FixtureBuild)(earlierTestCommand: FixtureBuild => TestCommandResult): Unit = {
    os.makeDir.all(build.markersFolder)
    val earlier = earlierTestCommand(build)
    withClue(earlier.debugString) {
      os.exists(build.jsonOutputFile) shouldBe true
    }
  }

  override protected def afterAll(): Unit =
    try {
      openTesters.foreach(_.close())
    } finally {
      super.afterAll()
    }
}
