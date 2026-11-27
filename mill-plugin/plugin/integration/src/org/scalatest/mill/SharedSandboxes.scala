package org.scalatest.mill

import mill.testkit.IntegrationTester
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Suite
import org.scalatest.matchers.should

import scala.collection.mutable

trait SharedSandboxes extends FixtureBuilds with BeforeAndAfterAll { this: Suite & should.Matchers =>

  private val openTesters: mutable.Buffer[IntegrationTester] = mutable.Buffer.empty

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

    def use[A](test: FixtureBuild => A): A = {
      reset.restore(build)
      test(build)
    }
  }

  protected def sharedSandbox(fixture: FixtureName, reset: SandboxReset)(setUp: FixtureBuild => Unit): SharedSandbox =
    new SharedSandbox(fixture, reset, setUp)

  override protected def afterAll(): Unit =
    try {
      openTesters.foreach(_.close())
    } finally {
      super.afterAll()
    }
}
