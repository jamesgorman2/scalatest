package org.scalatest.mill

import mill.testkit.IntegrationTester
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Suite
import org.scalatest.matchers.should

import scala.collection.mutable

trait SharedFixtureBuilds extends FixtureBuilds with BeforeAndAfterAll { this: Suite & should.Matchers =>

  private val openTesters: mutable.Buffer[IntegrationTester] = mutable.Buffer.empty

  private val savedEarlierReport: os.SubPath = os.sub / "earlier-report.json"

  // A shared build runs Mill without a daemon. A daemon would carry its environment and caches from one
  // scenario to the next, and some scenarios check how a run differs with and without one.
  protected final class SharedFixtureBuild(fixture: FixtureName, reset: WorkspaceReset, setUp: FixtureBuild => Unit) {

    private lazy val build: FixtureBuild = {
      val tester = integrationTester(fixture, daemonMode = false)
      openTesters += tester
      val build = new FixtureBuild(tester)
      setUp(build)
      build
    }

    // Every scenario reads the build file, so every reset restores it.
    private lazy val resetToTheSetUp: WorkspaceReset = reset.and(WorkspaceReset.buildFileHolding(build.buildText))

    def use[A](test: FixtureBuild => A): A = {
      resetToTheSetUp.restore(build)
      test(build)
    }
  }

  protected def sharedFixtureBuild(fixture: FixtureName, reset: WorkspaceReset)(setUp: FixtureBuild => Unit): SharedFixtureBuild =
    new SharedFixtureBuild(fixture, reset, setUp)

  protected def sharedFixtureBuildWithEarlierReport(fixture: FixtureName, setUp: FixtureBuild => Unit)(
      earlierTestCommand: FixtureBuild => TestCommandResult
  ): SharedFixtureBuild =
    sharedFixtureBuild(fixture, WorkspaceReset.emptyMarkersFolder.and(WorkspaceReset.jsonOutputFileAsSavedIn(savedEarlierReport))) { build =>
      setUp(build)
      writeEarlierReport(build)(earlierTestCommand)
      os.copy(build.jsonOutputFile, build.workspace / savedEarlierReport)
    }

  protected def writeEarlierReport(build: FixtureBuild)(earlierTestCommand: FixtureBuild => TestCommandResult): Unit = {
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
