package org.scalatest.mill

import mill.testkit.IntegrationTester
import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

final class CompatibilityFixtureSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with FixtureBuilds {

  private val fixtureSuiteFolders: Seq[os.Path] =
    sys.env("COMPATIBILITY_FIXTURE_SUITES").split(java.io.File.pathSeparatorChar).toSeq.map(os.Path(_))

  /** Copies the compatibility fixture's suites into the build's `core.test`. */
  private def addFixtureSuites(tester: IntegrationTester): Unit =
    for {
      folder <- fixtureSuiteFolders
      source <- os.list(folder)
    } {
      os.copy(source, tester.workspacePath / "core" / "test" / "src" / "compatibility" / "fixture" / source.last, createFolders = true)
    }

  private val expectedOutput: String =
    """[core.test]
      |  AnyFeatureSpecSpec:
      |    Feature: A feature
      |      Scenario: succeeds
      |      Scenario: fails *** FAILED ***
      |        3 did not equal 4 (FeatureSpecSpecs.scala:18)
      |      - Scenario: is ignored !!! IGNORED !!!
      |      Scenario: is pending (pending)
      |      Scenario: is cancelled !!! CANCELED !!!
      |        cancelled by the test (FeatureSpecSpecs.scala:27)
      |  AnyFlatSpecSpec:
      |    A subject
      |      - should succeed
      |      - must fail *** FAILED ***
      |        3 did not equal 4 (FlatSpecSpecs.scala:17)
      |      - should be ignored !!! IGNORED !!!
      |    Another subject
      |      - can be pending (pending)
      |      - should be cancelled !!! CANCELED !!!
      |        cancelled by the test (FlatSpecSpecs.scala:29)
      |  AnyFreeSpecSpec:
      |    A scope
      |      - succeeds
      |      - fails *** FAILED ***
      |        3 did not equal 4 (FreeSpecSpecs.scala:19)
      |      - is ignored !!! IGNORED !!!
      |      - is pending (pending)
      |      holding a nested scope
      |        - is cancelled !!! CANCELED !!!
      |          cancelled by the test (FreeSpecSpecs.scala:29)
      |    A pending scope (pending)
      |  AnyFunSpecSpec:
      |    A scope
      |      [info] info from a scope
      |      [note] note from a scope
      |      [alert] alert from a scope
      |      [markup] markup from a scope
      |      - succeeds
      |      - fails *** FAILED ***
      |        3 did not equal 4 (FunSpecSpecs.scala:24)
      |      - is ignored !!! IGNORED !!!
      |      - is pending (pending)
      |      - is cancelled !!! CANCELED !!!
      |        cancelled by the test (FunSpecSpecs.scala:33)
      |      - sends messages
      |        [info] info from a test
      |        [note] note from a test
      |        [alert] alert from a test
      |        [markup] markup from a test
      |      holding a nested scope
      |        - succeeds
      |    A pending scope (pending)
      |  AnyFunSuiteSpec:
      |    [info] info from a suite
      |    [note] note from a suite
      |    [alert] alert from a suite
      |    [markup] markup from a suite
      |    - succeeds
      |    - fails *** FAILED ***
      |      3 did not equal 4 (FunSuiteSpecs.scala:22)
      |    - is ignored !!! IGNORED !!!
      |    - is pending (pending)
      |    - is cancelled !!! CANCELED !!!
      |      cancelled by the test (FunSuiteSpecs.scala:31)
      |    - writes to stdout and to stderr
      |    - throws an exception that has a cause *** FAILED ***
      |      java.lang.IllegalStateException: outer
      |        at …
      |        ...
      |      Cause: java.io.IOException: inner
      |        at …
      |        ...
      |  AnyPropSpecSpec:
      |    - succeeds
      |    - fails *** FAILED ***
      |      scala.List.apply[scala.Int](1, 2, 3).forall(((_$2: scala.Int) => _$2.>(1))) was false (PropSpecSpecs.scala:12)
      |    - is ignored !!! IGNORED !!!
      |  AnyWordSpecSpec:
      |    A subject
      |      when in a state
      |        - should succeed
      |        - should fail *** FAILED ***
      |          3 did not equal 4 (WordSpecSpecs.scala:19)
      |        - should be ignored !!! IGNORED !!!
      |    Another subject
      |      - can be pending (pending)
      |      - can be cancelled !!! CANCELED !!!
      |        cancelled by the test (WordSpecSpecs.scala:31)
      |  AsyncFeatureSpecSpec:
      |    Feature: A feature
      |      - Scenario: succeeds
      |      - Scenario: fails *** FAILED ***
      |        3 did not equal 4 (FeatureSpecSpecs.scala:38)
      |      - Scenario: is ignored !!! IGNORED !!!
      |  AsyncFlatSpecSpec:
      |    A subject
      |      - should succeed
      |      - must fail *** FAILED ***
      |        3 did not equal 4 (FlatSpecSpecs.scala:38)
      |  AsyncFreeSpecSpec:
      |    A scope
      |      - succeeds
      |      - is cancelled !!! CANCELED !!!
      |        cancelled in a future (FreeSpecSpecs.scala:44)
      |  AsyncFunSpecSpec:
      |    A scope
      |      - succeeds
      |      - fails with an exception *** FAILED ***
      |        java.lang.IllegalArgumentException: thrown in a future
      |          at …
      |          ...
      |  AsyncFunSuiteSpec:
      |    - succeeds
      |    - fails *** FAILED ***
      |      3 did not equal 4 (FunSuiteSpecs.scala:47)
      |  AsyncWordSpecSpec:
      |    A subject
      |      when in a state
      |        - should succeed
      |    Another subject
      |      - can fail *** FAILED ***
      |        3 did not equal 4 (WordSpecSpecs.scala:46)
      |  BeforeAllThrowsSpec:
      |    compatibility.fixture.BeforeAllThrowsSpec *** ABORTED ***
      |      java.lang.IllegalStateException: beforeAll failed
      |        at …
      |        ...
      |    (1 test not run)
      |  ConfigMapWrappedSpec:
      |    A scope of a wrapped suite
      |      - succeeds
      |      - fails *** FAILED ***
      |        Map() was empty (ConfigMapWrappedSpec.scala:14)
      |  FixtureAnyFeatureSpecSpec:
      |    Feature: A feature
      |      Scenario: succeeds
      |      Scenario: fails without the fixture *** FAILED ***
      |        3 did not equal 4 (FeatureSpecSpecs.scala:56)
      |      - Scenario: is ignored !!! IGNORED !!!
      |      - Scenario: is ignored without the fixture !!! IGNORED !!!
      |  FixtureAnyFlatSpecSpec:
      |    A subject
      |      - should succeed
      |    Another subject
      |      - can be cancelled !!! CANCELED !!!
      |        "[]fixture" did not equal "[another ]fixture" (FlatSpecSpecs.scala:54)
      |  FixtureAnyFreeSpecSpec:
      |    A scope
      |      - succeeds
      |      - fails *** FAILED ***
      |        "[]fixture" did not equal "[another ]fixture" (FreeSpecSpecs.scala:59)
      |        Analysis:
      |        "[]fixture" -> "[another ]fixture"
      |  FixtureAnyFunSpecSpec:
      |    A scope
      |      - succeeds
      |      - is pending (pending)
      |  FixtureAnyFunSuiteSpec:
      |    - succeeds
      |    - is cancelled !!! CANCELED !!!
      |      "[]fixture" did not equal "[another ]fixture" (FunSuiteSpecs.scala:60)
      |  FixtureAnyPropSpecSpec:
      |    - succeeds
      |    - fails *** FAILED ***
      |      numbers.forall(((_$5: scala.Int) => _$5.>(1))) was false (PropSpecSpecs.scala:28)
      |  FixtureAnyWordSpecSpec:
      |    A subject
      |      when in a state
      |        - should succeed
      |    Another subject
      |      - can be cancelled !!! CANCELED !!!
      |        "[]fixture" did not equal "[another ]fixture" the fixture is not the one assumed (WordSpecSpecs.scala:65)
      |  FixtureAsyncFeatureSpecSpec:
      |    Feature: A feature
      |      - Scenario: succeeds
      |      - Scenario: fails *** FAILED ***
      |        "[]fixture" did not equal "[another ]fixture" (FeatureSpecSpecs.scala:77)
      |        Analysis:
      |        "[]fixture" -> "[another ]fixture"
      |      - Scenario: is ignored !!! IGNORED !!!
      |  FixtureAsyncFlatSpecSpec:
      |    A subject
      |      - should succeed
      |      - should be ignored !!! IGNORED !!!
      |  FixtureAsyncFreeSpecSpec:
      |    A scope
      |      - succeeds
      |      - is ignored !!! IGNORED !!!
      |  FixtureAsyncFunSpecSpec:
      |    A scope
      |      - succeeds
      |      - fails *** FAILED ***
      |        "[]fixture" did not equal "[another ]fixture" (FunSpecSpecs.scala:88)
      |        Analysis:
      |        "[]fixture" -> "[another ]fixture"
      |  FixtureAsyncFunSuiteSpec:
      |    - succeeds
      |    - is ignored !!! IGNORED !!!
      |  FixtureAsyncWordSpecSpec:
      |    A subject
      |      when in a state
      |        - should succeed
      |    Another subject
      |      - can be pending (pending)
      |  NestingSpec:
      |    FirstNestedSpec:
      |      - succeeds in the first nested suite
      |    SecondNestedSpec:
      |      - fails in the second nested suite *** FAILED ***
      |        3 did not equal 4 (NestingSpec.scala:19)
      |  PathAnyFreeSpecSpec:
      |    A scope
      |      - sends messages
      |        [info] info from a path test
      |        [note] note from a path test
      |        [alert] alert from a path test
      |        [markup] markup from a path test
      |      - is pending (pending)
      |      holding a nested scope
      |        - succeeds
      |  PathAnyFunSpecSpec:
      |    A scope
      |      - sends messages
      |        [info] info from a path test
      |        [note] note from a path test
      |        [alert] alert from a path test
      |        [markup] markup from a path test
      |      - fails *** FAILED ***
      |        3 did not equal 4 (FunSpecSpecs.scala:105)
      |      holding a nested scope
      |        - succeeds
      |  RefSpecSpec:
      |    A scope
      |      - fails in it *** FAILED ***
      |        3 did not equal 4 (RefSpecSpec.scala:10)
      |      - is ignored in it !!! IGNORED !!!
      |      - is pending in it (pending)
      |      - succeeds in it
      |  Output outside any suite:
      |    [stdout] a line on stdout
      |    [stderr] a line on stderr""".stripMargin

  Feature("Every suite, scope, test, failure and line of captured output a run holds is shown, apart from every node below the root of a collapsed subtree, a subtree whose root is aborted and in which no other node has an execution") {

    Scenario("The compatibility fixture") {
      withTester("compatibility-fixture") { tester =>
        Given("core.test's suites are the compatibility fixture")
        addFixtureSuites(tester)

        When("the test command runs core.test with Mill's --color false")
        val result = TestCommandResult(tester.eval(Seq("--color", "false", "scalatest/test", "core.test")))

        Then("the human-readable output shows every suite, scope and test, apart from every node below the root of a collapsed subtree, a subtree whose root is aborted and in which no other node has an execution, every failure with its message, and every line the fixture wrote to stdout or stderr")
        withClue(result.debugString) {
          CapturedLines.byStream(StackFrames.collapsed(result.humanReadableOutput)).mkString("\n") shouldBe expectedOutput.replace("\r\n", "\n")
        }
      }
    }
  }
}

/**
 * Replaces each run of a throwable's stack-frame lines with one line `at …`, keeping its indentation. The frames name the
 * fork's own internals, and an asynchronous test's frames depend on the thread it completes on.
 */
object StackFrames {

  private val frame = """\s*at \S.*""".r

  def collapsed(lines: Seq[String]): Seq[String] =
    lines.foldRight(List.empty[String]) { (line, following) =>
      if (!frame.matches(line)) {
        line :: following
      } else {
        val placeholder = line.takeWhile(_ == ' ') + "at …"
        if (following.headOption.contains(placeholder)) following else placeholder :: following
      }
    }
}

/**
 * Puts each run of captured lines in stream order, stdout's before stderr's, keeping each stream's own order. The worker's
 * stdout and stderr are read on threads of their own, so the order between the two streams varies from run to run.
 */
object CapturedLines {

  private def stream(line: String): Option[String] = Seq("[stdout] ", "[stderr] ").find(line.trim.startsWith)

  def byStream(lines: Seq[String]): Seq[String] =
    lines
      .foldRight(List.empty[List[String]]) { (line, groups) =>
        groups match {
          case group :: rest if stream(line).isDefined && stream(group.head).isDefined => (line :: group) :: rest
          case _ => List(line) :: groups
        }
      }
      .flatMap(group => if (stream(group.head).isDefined) group.sortBy(line => stream(line).contains("[stderr] ")) else group)
}
