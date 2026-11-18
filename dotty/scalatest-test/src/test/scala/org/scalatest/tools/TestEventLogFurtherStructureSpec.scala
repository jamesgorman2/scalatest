package org.scalatest.tools

import java.util.concurrent.ConcurrentLinkedQueue
import org.scalatest.GivenWhenThen
import org.scalatest.events.Event
import org.scalatest.events.EventLogComparison
import org.scalatest.events.RunAborted
import org.scalatest.events.ScopeClosed
import org.scalatest.events.ScopeOpened
import org.scalatest.events.SuiteAborted
import org.scalatest.events.SuiteCompleted
import org.scalatest.events.SuiteStarting
import org.scalatest.events.TestStarting
import org.scalatest.events.TestSucceeded
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.tools.TestEventLogLine.nodeEvent
import sbt.testing.EventHandler
import scala.jdk.CollectionConverters.*

final class TestEventLogFurtherStructureSpec extends AnyFeatureSpec with GivenWhenThen with Matchers with TestEventLogRuns {

  private final case class StatedRun(reportedToReporters: Vector[String], eventLogInNotation: String, eventLogInFormat: List[String])

  private def reported(events: Vector[Event]): Vector[String] = {
    events.collect {
      case starting: SuiteStarting => s"SuiteStarting ${starting.suiteName}"
      case completed: SuiteCompleted => s"SuiteCompleted ${completed.suiteName}"
      case aborted: SuiteAborted => s"SuiteAborted ${aborted.suiteName}: ${aborted.message}"
      case aborted: RunAborted => s"RunAborted: ${aborted.message}"
      case opened: ScopeOpened => s"ScopeOpened ${opened.message}"
      case closed: ScopeClosed => s"ScopeClosed ${closed.message}"
      case starting: TestStarting => s"TestStarting ${starting.testName}"
      case succeeded: TestSucceeded => s"TestSucceeded ${succeeded.testName}"
      case other => other.getClass.getSimpleName
    }
  }

  private val runStarted = TestEventLogLine.runStarted(1L, "2026-01-02T03:04:05.678Z")
  private val runCompleted = TestEventLogLine.runEvent("RunCompleted")
  private val runEndedCompleted = TestEventLogLine.runEnded("completed")

  private def runStartingOfFramework(configMap: String): String =
    TestEventLogLine.runEvent("RunStarting", s""""testCount":0,"configMap":{$configMap},""")

  private def frameworkRunOf(configMap: String, lines: String*): List[String] =
    runStarted :: runStartingOfFramework(configMap) :: lines.toList ::: List(runCompleted, runEndedCompleted)

  private def suiteAborted(suite: NodePath, message: String, throwable: String): String =
    nodeEvent(suite, List(1), "SuiteAborted", s""""message":"$message","throwable":$throwable,""")

  private val invocationTargetCausedByNoDb =
    """{"id":1,"className":"java.lang.reflect.InvocationTargetException","cause":{"id":2,"className":"java.lang.IllegalStateException","message":"no db"}}"""

  private def invocationTargetEncountered(suiteClassName: String): String =
    s"java.lang.reflect.InvocationTargetException encountered when attempting to run suite $suiteClassName"

  private val reportedOfConfigSpec = Vector(
    "SuiteStarting ConfigSpec",
    "ScopeOpened A config",
    "TestStarting A config reads",
    "TestSucceeded A config reads",
    "ScopeClosed A config",
    "SuiteCompleted ConfigSpec"
  )

  private def configSpecHolds(className: String): StatedRun = {
    val top = NodePath.topLevel(className)
    val aConfig = top.scope("A config")
    val reads = top.test("A config reads")
    StatedRun(
      Vector("RunStarting") ++ reportedOfConfigSpec ++ Vector("RunCompleted"),
      s"""
      structure
        suite "ConfigSpec", class "$className"
          scope "A config", label "A config"
            test "reads", label "- reads"
      suite "ConfigSpec": SuiteStarting
      test "reads": TestStarting
      test "reads": TestSucceeded
      """,
      List(
        TestEventLogLine.structure(
          TestEventLogLine.topLevelSuite("ConfigSpec", className),
          TestEventLogLine.scope(aConfig, 0, "A config", "A config"),
          TestEventLogLine.test(reads, aConfig, 0, "reads", "- reads")
        ),
        nodeEvent(top, List(1), "SuiteStarting"),
        nodeEvent(reads, List(1, 1, 1), "TestStarting"),
        nodeEvent(reads, List(1, 1, 1), "TestSucceeded")
      )
    )
  }

  private val configSpec = configSpecHolds("samples.ConfigSpec")

  private val configSpecDiscovered = {
    val stated = configSpecHolds("samples.discovered.ConfigSpec")
    stated.copy(
      reportedToReporters = Vector("DiscoveryStarting", "DiscoveryCompleted", "RunStarting", "SuiteStarting DiscoverySuite") ++
        reportedOfConfigSpec ++ Vector("SuiteCompleted DiscoverySuite", "RunCompleted")
    )
  }

  private def configDependent(suite: String): StatedRun = {
    val top = NodePath.topLevel(s"samples.$suite")
    val aConfig = top.scope("A config")
    val local = top.test("A config local")
    val remote = top.test("A config remote")
    StatedRun(
      Vector(
        "RunStarting",
        s"SuiteStarting $suite",
        "ScopeOpened A config",
        "TestStarting A config remote",
        "TestSucceeded A config remote",
        "ScopeClosed A config",
        s"SuiteCompleted $suite",
        "RunCompleted"
      ),
      s"""
      run started
      run: RunStarting
      structure
        suite "$suite", class "samples.$suite"
          scope "A config", label "A config"
            test "local", label "- local"
      suite "$suite": SuiteStarting
      structure
        suite "$suite", class "samples.$suite"
          scope "A config", label "A config"
            test "remote", label "- remote"
      scope "A config": ScopeOpened
      test "remote": TestStarting
      test "remote": TestSucceeded
      scope "A config": ScopeClosed
      suite "$suite": SuiteCompleted
      run: RunCompleted
      run ended completed
      """,
      frameworkRunOf(
        """"db":"on"""",
        TestEventLogLine.structure(
          TestEventLogLine.topLevelSuite(suite, s"samples.$suite"),
          TestEventLogLine.scope(aConfig, 0, "A config", "A config"),
          TestEventLogLine.test(local, aConfig, 0, "local", "- local")
        ),
        nodeEvent(top, List(1), "SuiteStarting"),
        TestEventLogLine.structure(
          TestEventLogLine.topLevelSuite(suite, s"samples.$suite"),
          TestEventLogLine.scope(aConfig, 0, "A config", "A config"),
          TestEventLogLine.test(remote, aConfig, 0, "remote", "- remote")
        ),
        nodeEvent(aConfig, List(1, 1), "ScopeOpened"),
        nodeEvent(remote, List(1, 1, 1), "TestStarting"),
        nodeEvent(remote, List(1, 1, 1), "TestSucceeded"),
        nodeEvent(aConfig, List(1, 1), "ScopeClosed"),
        nodeEvent(top, List(1), "SuiteCompleted")
      )
    )
  }

  private val configDependentSpec = configDependent("ConfigDependentSpec")

  private val asyncConfigDependentSpec = configDependent("AsyncConfigDependentSpec")

  private val configDependentIgnoredSpec: StatedRun = {
    val suite = "ConfigDependentIgnoredSpec"
    val top = NodePath.topLevel(s"samples.$suite")
    val aConfig = top.scope("A config")
    val local = top.test("A config local")
    val remote = top.test("A config remote")
    StatedRun(
      Vector(
        "RunStarting",
        s"SuiteStarting $suite",
        "ScopeOpened A config",
        "TestIgnored",
        "ScopeClosed A config",
        s"SuiteCompleted $suite",
        "RunCompleted"
      ),
      s"""
      run started
      run: RunStarting
      structure
        suite "$suite", class "samples.$suite"
          scope "A config", label "A config"
            test "local", label "- local"
      suite "$suite": SuiteStarting
      structure
        suite "$suite", class "samples.$suite"
          scope "A config", label "A config"
            test "remote", label "- remote", ignored
      scope "A config": ScopeOpened
      test "remote": TestIgnored
      scope "A config": ScopeClosed
      suite "$suite": SuiteCompleted
      run: RunCompleted
      run ended completed
      """,
      frameworkRunOf(
        """"db":"on"""",
        TestEventLogLine.structure(
          TestEventLogLine.topLevelSuite(suite, s"samples.$suite"),
          TestEventLogLine.scope(aConfig, 0, "A config", "A config"),
          TestEventLogLine.test(local, aConfig, 0, "local", "- local")
        ),
        nodeEvent(top, List(1), "SuiteStarting"),
        TestEventLogLine.structure(
          TestEventLogLine.topLevelSuite(suite, s"samples.$suite"),
          TestEventLogLine.scope(aConfig, 0, "A config", "A config"),
          TestEventLogLine.ignoredTest(remote, aConfig, 0, "remote", "- remote")
        ),
        nodeEvent(aConfig, List(1, 1), "ScopeOpened"),
        nodeEvent(remote, List(1, 1, 1), "TestIgnored"),
        nodeEvent(aConfig, List(1, 1), "ScopeClosed"),
        nodeEvent(top, List(1), "SuiteCompleted")
      )
    )
  }

  private val nestedConfigDependentSpec = NodePath.topLevel("samples.NestsConfigDependentSpec").suite("samples.ConfigDependentSpec")

  private val nestedAConfig = nestedConfigDependentSpec.scope("A config")

  private val nestedRemote = nestedConfigDependentSpec.test("A config remote")

  private val nestsConfigDependentSpec = StatedRun(
    Vector(
      "RunStarting",
      "SuiteStarting NestsConfigDependentSpec",
      "SuiteStarting ConfigDependentSpec",
      "ScopeOpened A config",
      "TestStarting A config remote",
      "TestSucceeded A config remote",
      "ScopeClosed A config",
      "SuiteCompleted ConfigDependentSpec",
      "SuiteCompleted NestsConfigDependentSpec",
      "RunCompleted"
    ),
    """
    structure
      suite "NestsConfigDependentSpec", class "samples.NestsConfigDependentSpec"
        suite "ConfigDependentSpec", class "samples.ConfigDependentSpec"
          scope "A config", label "A config"
            test "local", label "- local"
    suite "NestsConfigDependentSpec": SuiteStarting
    suite "ConfigDependentSpec": SuiteStarting
    structure under suite "NestsConfigDependentSpec"
      suite "ConfigDependentSpec", class "samples.ConfigDependentSpec"
        scope "A config", label "A config"
          test "remote", label "- remote"
    scope "A config": ScopeOpened
    test "remote": TestStarting
    test "remote": TestSucceeded
    scope "A config": ScopeClosed
    suite "ConfigDependentSpec": SuiteCompleted
    suite "NestsConfigDependentSpec": SuiteCompleted
    """,
    List(
      TestEventLogLine.structure(
        TestEventLogLine.topLevelSuite("NestsConfigDependentSpec", "samples.NestsConfigDependentSpec"),
        TestEventLogLine.nestedSuite(nestedConfigDependentSpec, 0, "ConfigDependentSpec", "samples.ConfigDependentSpec"),
        TestEventLogLine.scope(nestedAConfig, 0, "A config", "A config"),
        TestEventLogLine.test(nestedConfigDependentSpec.test("A config local"), nestedAConfig, 0, "local", "- local")
      ),
      nodeEvent(nestedConfigDependentSpec.parent, List(1), "SuiteStarting"),
      nodeEvent(nestedConfigDependentSpec, List(1, 1), "SuiteStarting"),
      TestEventLogLine.structure(
        TestEventLogLine.nestedSuite(nestedConfigDependentSpec, 0, "ConfigDependentSpec", "samples.ConfigDependentSpec"),
        TestEventLogLine.scope(nestedAConfig, 0, "A config", "A config"),
        TestEventLogLine.test(nestedRemote, nestedAConfig, 0, "remote", "- remote")
      ),
      nodeEvent(nestedAConfig, List(1, 1, 1), "ScopeOpened"),
      nodeEvent(nestedRemote, List(1, 1, 1, 1), "TestStarting"),
      nodeEvent(nestedRemote, List(1, 1, 1, 1), "TestSucceeded"),
      nodeEvent(nestedAConfig, List(1, 1, 1), "ScopeClosed"),
      nodeEvent(nestedConfigDependentSpec, List(1, 1), "SuiteCompleted"),
      nodeEvent(nestedConfigDependentSpec.parent, List(1), "SuiteCompleted")
    )
  )

  private val oneInstancePerTest = NodePath.topLevel("samples.OneInstancePerTestSuite")

  private val oneInstancePerTestSuite = StatedRun(
    Vector(
      "RunStarting",
      "SuiteStarting OneInstancePerTestSuite",
      "TestStarting a",
      "TestSucceeded a",
      "TestStarting b",
      "TestSucceeded b",
      "SuiteCompleted OneInstancePerTestSuite",
      "RunCompleted"
    ),
    """
    structure
      suite "OneInstancePerTestSuite", class "samples.OneInstancePerTestSuite"
        test "a", label "- a"
        test "b", label "- b"
    suite "OneInstancePerTestSuite": SuiteStarting
    test "a": TestStarting
    test "a": TestSucceeded
    test "b": TestStarting
    test "b": TestSucceeded
    suite "OneInstancePerTestSuite": SuiteCompleted
    """,
    List(
      TestEventLogLine.structure(
        TestEventLogLine.topLevelSuite("OneInstancePerTestSuite", "samples.OneInstancePerTestSuite"),
        TestEventLogLine.test(oneInstancePerTest.test("a"), oneInstancePerTest, 0, "a", "- a"),
        TestEventLogLine.test(oneInstancePerTest.test("b"), oneInstancePerTest, 1, "b", "- b")
      ),
      nodeEvent(oneInstancePerTest, List(1), "SuiteStarting"),
      nodeEvent(oneInstancePerTest.test("a"), List(1, 1), "TestStarting"),
      nodeEvent(oneInstancePerTest.test("a"), List(1, 1), "TestSucceeded"),
      nodeEvent(oneInstancePerTest.test("b"), List(1, 1), "TestStarting"),
      nodeEvent(oneInstancePerTest.test("b"), List(1, 1), "TestSucceeded"),
      nodeEvent(oneInstancePerTest, List(1), "SuiteCompleted")
    )
  )

  private val tagsOverride = NodePath.topLevel("samples.TagsOverrideSuite")

  private val tagsOverrideSuite = StatedRun(
    Vector("RunStarting", "SuiteStarting TagsOverrideSuite", "TestStarting a", "TestSucceeded a", "SuiteCompleted TagsOverrideSuite", "RunCompleted"),
    """
    structure
      suite "TagsOverrideSuite", class "samples.TagsOverrideSuite"
        test "a", label "- a"
    suite "TagsOverrideSuite": SuiteStarting
    test "a": TestStarting
    test "a": TestSucceeded
    suite "TagsOverrideSuite": SuiteCompleted
    """,
    List(
      TestEventLogLine.structure(
        TestEventLogLine.topLevelSuite("TagsOverrideSuite", "samples.TagsOverrideSuite"),
        TestEventLogLine.test(tagsOverride.test("a"), tagsOverride, 0, "a", "- a")
      ),
      nodeEvent(tagsOverride, List(1), "SuiteStarting"),
      nodeEvent(tagsOverride.test("a"), List(1, 1), "TestStarting"),
      nodeEvent(tagsOverride.test("a"), List(1, 1), "TestSucceeded"),
      nodeEvent(tagsOverride, List(1), "SuiteCompleted")
    )
  )

  private val sharedTest = NodePath.topLevel("samples.ConfigDependentWithSharedTestSpec")

  private val sharedTestAConfig = sharedTest.scope("A config")

  private val configDependentWithSharedTestSpec = StatedRun(
    Vector(
      "RunStarting",
      "SuiteStarting ConfigDependentWithSharedTestSpec",
      "ScopeOpened A config",
      "TestStarting A config reads",
      "TestSucceeded A config reads",
      "TestStarting A config remote",
      "TestSucceeded A config remote",
      "ScopeClosed A config",
      "SuiteCompleted ConfigDependentWithSharedTestSpec",
      "RunCompleted"
    ),
    """
    run started
    run: RunStarting
    structure
      suite "ConfigDependentWithSharedTestSpec", class "samples.ConfigDependentWithSharedTestSpec"
        scope "A config", label "A config"
          test "reads", label "- reads"
          test "local", label "- local"
    suite "ConfigDependentWithSharedTestSpec": SuiteStarting
    structure
      suite "ConfigDependentWithSharedTestSpec", class "samples.ConfigDependentWithSharedTestSpec"
        scope "A config", label "A config"
          test "reads", label "- reads"
          test "remote", label "- remote"
    scope "A config": ScopeOpened
    test "reads": TestStarting
    test "reads": TestSucceeded
    test "remote": TestStarting
    test "remote": TestSucceeded
    scope "A config": ScopeClosed
    suite "ConfigDependentWithSharedTestSpec": SuiteCompleted
    run: RunCompleted
    run ended completed
    """,
    frameworkRunOf(
      """"db":"on"""",
      TestEventLogLine.structure(
        TestEventLogLine.topLevelSuite("ConfigDependentWithSharedTestSpec", "samples.ConfigDependentWithSharedTestSpec"),
        TestEventLogLine.scope(sharedTestAConfig, 0, "A config", "A config"),
        TestEventLogLine.test(sharedTest.test("A config reads"), sharedTestAConfig, 0, "reads", "- reads"),
        TestEventLogLine.test(sharedTest.test("A config local"), sharedTestAConfig, 1, "local", "- local")
      ),
      nodeEvent(sharedTest, List(1), "SuiteStarting"),
      TestEventLogLine.structure(
        TestEventLogLine.topLevelSuite("ConfigDependentWithSharedTestSpec", "samples.ConfigDependentWithSharedTestSpec"),
        TestEventLogLine.scope(sharedTestAConfig, 0, "A config", "A config"),
        TestEventLogLine.test(sharedTest.test("A config reads"), sharedTestAConfig, 0, "reads", "- reads"),
        TestEventLogLine.test(sharedTest.test("A config remote"), sharedTestAConfig, 1, "remote", "- remote")
      ),
      nodeEvent(sharedTestAConfig, List(1, 1), "ScopeOpened"),
      nodeEvent(sharedTest.test("A config reads"), List(1, 1, 1), "TestStarting"),
      nodeEvent(sharedTest.test("A config reads"), List(1, 1, 1), "TestSucceeded"),
      nodeEvent(sharedTest.test("A config remote"), List(1, 1, 1), "TestStarting"),
      nodeEvent(sharedTest.test("A config remote"), List(1, 1, 1), "TestSucceeded"),
      nodeEvent(sharedTestAConfig, List(1, 1), "ScopeClosed"),
      nodeEvent(sharedTest, List(1), "SuiteCompleted")
    )
  )

  private val configWithoutDbFailsSpec = StatedRun(
    Vector("RunStarting", "RunCompleted"),
    """
    run started
    run: RunStarting
    structure
      suite "ConfigWithoutDbFailsSpec", class "samples.ConfigWithoutDbFailsSpec"
    suite "ConfigWithoutDbFailsSpec": SuiteStarting
    suite "ConfigWithoutDbFailsSpec": SuiteAborted(throwable: java.lang.reflect.InvocationTargetException caused by java.lang.IllegalStateException "no db")
    run: RunCompleted
    run ended completed
    """,
    frameworkRunOf(
      "",
      TestEventLogLine.structure(TestEventLogLine.topLevelSuite("ConfigWithoutDbFailsSpec", "samples.ConfigWithoutDbFailsSpec")),
      nodeEvent(NodePath.topLevel("samples.ConfigWithoutDbFailsSpec"), List(1), "SuiteStarting"),
      suiteAborted(NodePath.topLevel("samples.ConfigWithoutDbFailsSpec"), invocationTargetEncountered("samples.ConfigWithoutDbFailsSpec"), invocationTargetCausedByNoDb)
    )
  )

  private def runAborted(message: String): String =
    TestEventLogLine.runEvent("RunAborted", s""""message":"$message","throwable":$invocationTargetCausedByNoDb,""")

  private def configWithoutDbFailsUnderRunner(runAbortedMessage: String, reportedBeforeRunAborted: Vector[String]): StatedRun =
    StatedRun(
      reportedBeforeRunAborted :+ s"RunAborted: $runAbortedMessage",
      """
      run started
      run: RunAborted(throwable: java.lang.reflect.InvocationTargetException caused by java.lang.IllegalStateException "no db")
      run ended aborted
      """,
      List(runStarted, runAborted(runAbortedMessage), TestEventLogLine.runEnded("aborted"))
    )

  private val runAbortedByConstructor = "An exception or error caused a run to abort. "

  private def structureOfAConfigReads(name: String, className: String): String = {
    val suite = NodePath.topLevel(className)
    TestEventLogLine.structure(
      TestEventLogLine.topLevelSuite(name, className),
      TestEventLogLine.scope(suite.scope("A config"), 0, "A config", "A config"),
      TestEventLogLine.test(suite.test("A config reads"), suite.scope("A config"), 0, "reads", "- reads")
    )
  }

  private val configSetupFailsSpec = StatedRun(
    Vector("RunStarting", "SuiteStarting ConfigSetupFailsSpec", "SuiteAborted ConfigSetupFailsSpec: database unreachable", "RunCompleted"),
    """
    structure
      suite "ConfigSetupFailsSpec", class "samples.ConfigSetupFailsSpec"
        scope "A config", label "A config"
          test "reads", label "- reads"
    suite "ConfigSetupFailsSpec": SuiteAborted(throwable: java.lang.RuntimeException "database unreachable")
    """,
    List(
      structureOfAConfigReads("ConfigSetupFailsSpec", "samples.ConfigSetupFailsSpec"),
      suiteAborted(
        NodePath.topLevel("samples.ConfigSetupFailsSpec"),
        "database unreachable",
        """{"id":1,"className":"java.lang.RuntimeException","message":"database unreachable"}"""
      )
    )
  )

  private val configWithDbFailsSpec = StatedRun(
    Vector(
      "RunStarting",
      "SuiteStarting ConfigWithDbFailsSpec",
      s"SuiteAborted ConfigWithDbFailsSpec: ${invocationTargetEncountered("samples.ConfigWithDbFailsSpec")}",
      "RunCompleted"
    ),
    """
    structure
      suite "ConfigWithDbFailsSpec", class "samples.ConfigWithDbFailsSpec"
        scope "A config", label "A config"
          test "reads", label "- reads"
    suite "ConfigWithDbFailsSpec": SuiteAborted(throwable: java.lang.reflect.InvocationTargetException caused by java.lang.IllegalStateException "no db")
    """,
    List(
      structureOfAConfigReads("ConfigWithDbFailsSpec", "samples.ConfigWithDbFailsSpec"),
      suiteAborted(
        NodePath.topLevel("samples.ConfigWithDbFailsSpec"),
        invocationTargetEncountered("samples.ConfigWithDbFailsSpec"),
        invocationTargetCausedByNoDb
      )
    )
  )

  private val configNamedTest = NodePath.topLevel("samples.ConfigNamedTestSuite")

  private val configNamedTestSuite = StatedRun(
    Vector("RunStarting", "SuiteStarting ConfigNamedTestSuite", "SuiteCompleted ConfigNamedTestSuite", "RunCompleted"),
    """
    run started
    run: RunStarting
    structure
      suite "ConfigNamedTestSuite", class "samples.ConfigNamedTestSuite"
        test "local", label "- local"
    suite "ConfigNamedTestSuite": SuiteStarting
    structure
      suite "ConfigNamedTestSuite", class "samples.ConfigNamedTestSuite"
        test "remote", label "- remote"
    suite "ConfigNamedTestSuite": SuiteCompleted
    run: RunCompleted
    run ended completed
    """,
    frameworkRunOf(
      """"db":"on"""",
      TestEventLogLine.structure(
        TestEventLogLine.topLevelSuite("ConfigNamedTestSuite", "samples.ConfigNamedTestSuite"),
        TestEventLogLine.test(configNamedTest.test("local"), configNamedTest, 0, "local", "- local")
      ),
      nodeEvent(configNamedTest, List(1), "SuiteStarting"),
      TestEventLogLine.structure(
        TestEventLogLine.topLevelSuite("ConfigNamedTestSuite", "samples.ConfigNamedTestSuite"),
        TestEventLogLine.test(configNamedTest.test("remote"), configNamedTest, 0, "remote", "- remote")
      ),
      nodeEvent(configNamedTest, List(1), "SuiteCompleted")
    )
  )

  private val runsADroppedTest = NodePath.topLevel("samples.RunsADroppedTestSuite")

  private val runsADroppedTestSuite = StatedRun(
    Vector(
      "RunStarting",
      "SuiteStarting RunsADroppedTestSuite",
      "TestStarting a",
      "TestSucceeded a",
      "TestStarting b",
      "TestSucceeded b",
      "SuiteCompleted RunsADroppedTestSuite",
      "RunCompleted"
    ),
    """
    run started
    run: RunStarting
    structure
      suite "RunsADroppedTestSuite", class "samples.RunsADroppedTestSuite"
        test "a", label "- a"
    suite "RunsADroppedTestSuite": SuiteStarting
    test "a": TestStarting
    test "a": TestSucceeded
    structure
      suite "RunsADroppedTestSuite", class "samples.RunsADroppedTestSuite"
        test "a", label "- a"
        test "b", label "- b"
    test "b": TestStarting
    test "b": TestSucceeded
    suite "RunsADroppedTestSuite": SuiteCompleted
    run: RunCompleted
    run ended completed
    """,
    frameworkRunOf(
      "",
      TestEventLogLine.structure(
        TestEventLogLine.topLevelSuite("RunsADroppedTestSuite", "samples.RunsADroppedTestSuite"),
        TestEventLogLine.test(runsADroppedTest.test("a"), runsADroppedTest, 0, "a", "- a")
      ),
      nodeEvent(runsADroppedTest, List(1), "SuiteStarting"),
      nodeEvent(runsADroppedTest.test("a"), List(1, 1), "TestStarting"),
      nodeEvent(runsADroppedTest.test("a"), List(1, 1), "TestSucceeded"),
      TestEventLogLine.structure(
        TestEventLogLine.topLevelSuite("RunsADroppedTestSuite", "samples.RunsADroppedTestSuite"),
        TestEventLogLine.test(runsADroppedTest.test("a"), runsADroppedTest, 0, "a", "- a"),
        TestEventLogLine.test(runsADroppedTest.test("b"), runsADroppedTest, 1, "b", "- b")
      ),
      nodeEvent(runsADroppedTest.test("b"), List(1, 1), "TestStarting"),
      nodeEvent(runsADroppedTest.test("b"), List(1, 1), "TestSucceeded"),
      nodeEvent(runsADroppedTest, List(1), "SuiteCompleted")
    )
  )

  private def structuresIn(lines: Seq[String]): Int = lines.count(_.contains(""""item":"structure""""))

  private final case class SuiteNameAndClassName(suiteName: String, className: String)

  private def runAndDecoderScenarios(
    scenario: String,
    suite: String,
    suiteClassName: String,
    eventLogHolds: String,
    stated: StatedRun,
    launch: Launch = Launch.ByFramework,
    arguments: List[String] = Nil,
    eventLogIsOnlyTheStated: Boolean = true,
    structures: Option[Int] = None,
    classNameOfEveryStructure: Option[SuiteNameAndClassName] = None
  ): Unit = {
    Scenario(scenario) {
      thisProcess
      Given(suite)
      When(s"${launch.named} runs it without the runner argument")
      val reportedWithoutTheArgument = reported(eventsReportedWhenLaunched(launch, suiteClassName, arguments))

      Then("the run's reporters are told what ScalaTest 3.2.20 tells them")
      reportedWithoutTheArgument shouldBe stated.reportedToReporters

      TemporaryEventLog.in { eventLogFile =>
        When(s"${launch.named} runs it with ${("the runner argument" :: arguments).mkString(" and ")}")
        eventsReportedWhenLaunched(launch, suiteClassName, arguments ++ runnerArgument(eventLogFile))
        val lines = TemporaryEventLog.lines(eventLogFile)

        Then(s"the event log ${if (eventLogIsOnlyTheStated) "is" else "holds, in order,"} $eventLogHolds")
        if (eventLogIsOnlyTheStated) {
          StatedLines.mismatchWhenIs(lines, stated.eventLogInFormat) shouldBe None
          EventLogComparison.mismatchWhenIs(decodedOrFail(lines), built(stated.eventLogInNotation)) shouldBe None
        } else {
          StatedLines.mismatchWhenHoldsInOrder(lines, stated.eventLogInFormat) shouldBe None
          EventLogComparison.mismatchWhenHoldsInOrder(decodedOrFail(lines), built(stated.eventLogInNotation)) shouldBe None
        }
        structures.foreach { count =>
          And(s"the event log holds $count structure${if (count == 1) "" else "s"}")
          structuresIn(lines) shouldBe count
        }
        classNameOfEveryStructure.foreach { case SuiteNameAndClassName(suiteName, className) =>
          And(s"every structure holding $suiteName gives it class name $className")
          val classNames = classNamesGivenTo(suiteName, decodedOrFail(lines))
          classNames should not be empty
          classNames.distinct shouldBe Vector(className)
        }
      }
    }

    decoderScenario(scenario, eventLogHolds, stated)
  }

  private def decoderScenario(scenario: String, eventLogHolds: String, stated: StatedRun): Unit = {
    Scenario(s"The decoder reads the event log of: $scenario") {
      When("the decoder is given the lines of that event log")
      val events = decodedOrFail(stated.eventLogInFormat)

      Then(s"it yields $eventLogHolds")
      EventLogComparison.mismatchWhenIs(events, built(stated.eventLogInNotation)) shouldBe None
    }
  }

  private def classFilesOf(simpleNames: String*): List[String] = simpleNames.toList.map(name => s"samples/discovered/$name.class")

  Feature("A suite run through ConfigMapWrapperSuite") {

    runAndDecoderScenarios(
      "A suite run through ConfigMapWrapperSuite, by Framework",
      "a suite annotated @WrapWith(classOf[ConfigMapWrapperSuite]), declaring scope 'A config' holding test 'reads'",
      "samples.ConfigSpec",
      "the structure of the wrapped class, then the suite's events, and no other structure",
      configSpec,
      eventLogIsOnlyTheStated = false,
      structures = Some(1)
    )

    runAndDecoderScenarios(
      "A suite run through ConfigMapWrapperSuite, by Runner.run with -s",
      "a suite annotated @WrapWith(classOf[ConfigMapWrapperSuite]), declaring scope 'A config' holding test 'reads'",
      "samples.ConfigSpec",
      "the structure of the wrapped class, then the suite's events, and no other structure",
      configSpec,
      launch = Launch.ByRunner,
      eventLogIsOnlyTheStated = false,
      structures = Some(1)
    )

    Scenario("A suite run through ConfigMapWrapperSuite, by Runner.run with the runpath given with -R and no -s") {
      thisProcess
      TemporaryEventLog.in { eventLogFile =>
        Given("a runpath holding one suite annotated @WrapWith(classOf[ConfigMapWrapperSuite]), declaring scope 'A config' holding test 'reads'")
        withRunpathOf(eventLogFile.resolveSibling("runpath"), classFilesOf("ConfigSpec")) { runpath =>
          When("Runner.run discovers and runs the runpath's suites without the runner argument, and again with it")
          val arguments = List("-R", runpath, "-C", classOf[RunRecordingReporter].getName)
          val reportedWithoutTheArgument = reported(RunRecordingReporter.eventsReportedIn(runnerRun(arguments)))
          RunRecordingReporter.eventsReportedIn(runnerRun(arguments ++ runnerArgument(eventLogFile)))
          val lines = TemporaryEventLog.lines(eventLogFile)

          Then("the run's reporters are told what ScalaTest 3.2.20 tells them, without the argument")
          reportedWithoutTheArgument shouldBe configSpecDiscovered.reportedToReporters

          And("the event log holds, in order, the structure of the wrapped class, then the suite's events, and no other structure")
          StatedLines.mismatchWhenHoldsInOrder(lines, configSpecDiscovered.eventLogInFormat) shouldBe None
          EventLogComparison.mismatchWhenHoldsInOrder(decodedOrFail(lines), built(configSpecDiscovered.eventLogInNotation)) shouldBe None
          structuresIn(lines) shouldBe 1
        }
      }
    }

    decoderScenario(
      "A suite run through ConfigMapWrapperSuite, by Runner.run with the runpath given with -R and no -s",
      "the structure of the wrapped class, then the suite's events",
      configSpecDiscovered
    )

    runAndDecoderScenarios(
      "What a suite run through ConfigMapWrapperSuite registers depends on its config map",
      "a suite annotated @WrapWith(classOf[ConfigMapWrapperSuite]), declaring scope 'A config' holding test 'remote' " +
        "where its config map holds db, and test 'local' where it does not",
      "samples.ConfigDependentSpec",
      "the structure the empty config map registers, the suite's SuiteStarting, then a further structure of what the " +
        "run's config map registers, before the events of the scope and test it holds",
      configDependentSpec,
      arguments = List("-Ddb=on")
    )

    runAndDecoderScenarios(
      "A further structure marks an ignored test that only the suite instance that runs registers",
      "a suite annotated @WrapWith(classOf[ConfigMapWrapperSuite]), declaring scope 'A config' holding test 'remote', " +
        "declared with ignore, where its config map holds db, and test 'local' where it does not",
      "samples.ConfigDependentIgnoredSpec",
      "the structure the empty config map registers, then the suite's SuiteStarting, then a further structure of what the " +
        "run's config map registers, marking remote ignored, then remote's TestIgnored",
      configDependentIgnoredSpec,
      arguments = List("-Ddb=on")
    )

    runAndDecoderScenarios(
      "A scope and a test that both structures of a suite run through ConfigMapWrapperSuite contain",
      "a suite annotated @WrapWith(classOf[ConfigMapWrapperSuite]), declaring scope 'A config' holding test 'reads', " +
        "then test 'remote' where its config map holds db, and test 'local' where it does not",
      "samples.ConfigDependentWithSharedTestSpec",
      "a further structure in which the suite, scope 'A config' and test 'reads' have the node paths the first gives them, " +
        "and 'remote' a node path the first gives no node",
      configDependentWithSharedTestSpec,
      arguments = List("-Ddb=on")
    )

    Scenario("A wrapped suite's constructor throws given an empty config map, by Framework") {
      thisProcess
      Given("a suite annotated @WrapWith(classOf[ConfigMapWrapperSuite]), whose constructor throws IllegalStateException(\"no db\") where its config map lacks db")
      val suiteClassName = "samples.ConfigWithoutDbFailsSpec"
      val thrownByTheTask = "java.lang.reflect.InvocationTargetException caused by java.lang.IllegalStateException: no db"
      def thrownAndReported(arguments: List[String], eventHandler: EventHandler): (String, Vector[String]) = {
        var thrown = "nothing"
        val events = RunRecordingReporter.eventsReportedIn {
          val runner = frameworkRunner(arguments ++ List("-C", classOf[RunRecordingReporter].getName))
          try {
            scala.util.Try(execute(runner, suiteClassName, eventHandler, new RecordingLogger, Nil)).failed.foreach { failure =>
              thrown = s"${failure.getClass.getName} caused by ${failure.getCause.getClass.getName}: ${failure.getCause.getMessage}"
            }
          } finally runner.done()
        }
        (thrown, reported(events))
      }

      When("Framework runs it without the runner argument")
      thrownAndReported(Nil, _ => ()) shouldBe ((thrownByTheTask, configWithoutDbFailsSpec.reportedToReporters))

      TemporaryEventLog.in { eventLogFile =>
        When("Framework runs it with the runner argument")
        val handled = new ConcurrentLinkedQueue[sbt.testing.Event]
        val (thrown, _) = thrownAndReported(runnerArgument(eventLogFile), event => handled.add(event))
        val lines = TemporaryEventLog.lines(eventLogFile)

        Then("the execute of the task for the suite throws that InvocationTargetException, and the build tool's test-interface event handler receives no event for the suite")
        thrown shouldBe thrownByTheTask
        handled.asScala.toVector.map(_.fullyQualifiedName) shouldBe Vector.empty

        And("the event log is the suite's structure, its SuiteStarting and its SuiteAborted, and the run's events")
        StatedLines.mismatchWhenIs(lines, configWithoutDbFailsSpec.eventLogInFormat) shouldBe None
        EventLogComparison.mismatchWhenIs(decodedOrFail(lines), built(configWithoutDbFailsSpec.eventLogInNotation)) shouldBe None
      }
    }

    decoderScenario(
      "A wrapped suite's constructor throws given an empty config map, by Framework",
      "the suite's structure, its SuiteStarting and its SuiteAborted, and the run's events",
      configWithoutDbFailsSpec
    )

    val configWithoutDbFailsBySuite = configWithoutDbFailsUnderRunner(runAbortedByConstructor, Vector.empty)

    Scenario("A wrapped suite's constructor throws given an empty config map, by Runner.run with -s") {
      thisProcess
      Given("a suite annotated @WrapWith(classOf[ConfigMapWrapperSuite]), whose constructor throws IllegalStateException(\"no db\") where its config map lacks db")
      When("Runner.run runs it without the runner argument")
      val reportedWithoutTheArgument = reported(eventsReportedWhenLaunched(Launch.ByRunner, "samples.ConfigWithoutDbFailsSpec", Nil))

      Then("the run's reporters are told what ScalaTest 3.2.20 tells them")
      reportedWithoutTheArgument shouldBe configWithoutDbFailsBySuite.reportedToReporters

      TemporaryEventLog.in { eventLogFile =>
        When("Runner.run runs it with the runner argument")
        eventsReportedWhenLaunched(Launch.ByRunner, "samples.ConfigWithoutDbFailsSpec", runnerArgument(eventLogFile))
        val lines = TemporaryEventLog.lines(eventLogFile)

        Then("the event log holds, in order, the run's RunAborted and its end, and no structure or SuiteAborted")
        StatedLines.mismatchWhenHoldsInOrder(lines, configWithoutDbFailsBySuite.eventLogInFormat) shouldBe None
        EventLogComparison.mismatchWhenHoldsInOrder(decodedOrFail(lines), built(configWithoutDbFailsBySuite.eventLogInNotation)) shouldBe None
        structuresIn(lines) shouldBe 0
        lines.exists(_.contains(""""event":"SuiteAborted"""")) shouldBe false
      }
    }

    decoderScenario(
      "A wrapped suite's constructor throws given an empty config map, by Runner.run with -s",
      "the run's RunAborted and its end",
      configWithoutDbFailsBySuite
    )

    val configWithoutDbFailsDiscovered =
      configWithoutDbFailsUnderRunner(runAbortedByConstructor, Vector("DiscoveryStarting", "DiscoveryCompleted"))

    Scenario("A wrapped suite's constructor throws given an empty config map, by Runner.run with the runpath given with -R and no -s") {
      thisProcess
      TemporaryEventLog.in { eventLogFile =>
        Given("a runpath holding one suite annotated @WrapWith(classOf[ConfigMapWrapperSuite]), whose constructor throws where its config map lacks db")
        val runpathClasses = classFilesOf("ConfigWithoutDbFailsSpec", "BrokenCtorSpec", "BrokenCtorSpec$")
        withRunpathOf(eventLogFile.resolveSibling("runpath"), runpathClasses) { runpath =>
          When("Runner.run discovers and runs the runpath's suites without the runner argument, and again with it")
          val arguments = List("-R", runpath, "-C", classOf[RunRecordingReporter].getName)
          val whereTheConstructorThrows = Some(samples.discovered.BrokenCtorSpec.threadGroupItsConstructorThrowsIn)
          val reportedWithoutTheArgument =
            reported(RunRecordingReporter.eventsReportedIn(runnerRun(arguments, whereTheConstructorThrows)))
          RunRecordingReporter.eventsReportedIn(runnerRun(arguments ++ runnerArgument(eventLogFile), whereTheConstructorThrows))
          val lines = TemporaryEventLog.lines(eventLogFile)

          Then("the run's reporters are told what ScalaTest 3.2.20 tells them, without the argument")
          reportedWithoutTheArgument shouldBe configWithoutDbFailsDiscovered.reportedToReporters

          And("the event log holds, in order, the run's RunAborted and its end, and no structure or SuiteAborted")
          StatedLines.mismatchWhenHoldsInOrder(lines, configWithoutDbFailsDiscovered.eventLogInFormat) shouldBe None
          EventLogComparison.mismatchWhenHoldsInOrder(decodedOrFail(lines), built(configWithoutDbFailsDiscovered.eventLogInNotation)) shouldBe None
          structuresIn(lines) shouldBe 0
          lines.exists(_.contains(""""event":"SuiteAborted"""")) shouldBe false
        }
      }
    }

    decoderScenario(
      "A wrapped suite's constructor throws given an empty config map, by Runner.run with the runpath given with -R and no -s",
      "the run's RunAborted and its end",
      configWithoutDbFailsDiscovered
    )
  }

  Feature("A suite's class name, for a suite run through ConfigMapWrapperSuite") {

    runAndDecoderScenarios(
      "A wrapped suite's beforeAll throws",
      "a suite annotated @WrapWith(classOf[ConfigMapWrapperSuite]), mixing in BeforeAndAfterAll, whose beforeAll throws",
      "samples.ConfigSetupFailsSpec",
      "a structure holding the suite, every structure holding it giving it the wrapped class's name, and in this order after it the suite's SuiteAborted",
      configSetupFailsSpec,
      eventLogIsOnlyTheStated = false,
      classNameOfEveryStructure = Some(SuiteNameAndClassName("ConfigSetupFailsSpec", "samples.ConfigSetupFailsSpec"))
    )

    runAndDecoderScenarios(
      "A wrapped suite's constructor throws given the run's config map",
      "a suite annotated @WrapWith(classOf[ConfigMapWrapperSuite]), whose constructor throws where its config map holds db",
      "samples.ConfigWithDbFailsSpec",
      "a structure holding the suite, every structure holding it giving it the wrapped class's name, and in this order after it the suite's SuiteAborted",
      configWithDbFailsSpec,
      arguments = List("-Ddb=on"),
      eventLogIsOnlyTheStated = false,
      classNameOfEveryStructure = Some(SuiteNameAndClassName("ConfigWithDbFailsSpec", "samples.ConfigWithDbFailsSpec"))
    )
  }

  Feature("Further structures") {

    runAndDecoderScenarios(
      "A suite with no registration tree runs other tests than its first structure contains",
      "a Suite run through ConfigMapWrapperSuite whose one test is 'remote' where its config map holds db, and 'local' where it does not",
      "samples.ConfigNamedTestSuite",
      "the structure the empty config map gives, the suite's SuiteStarting, then a further structure holding 'remote'",
      configNamedTestSuite,
      arguments = List("-Ddb=on")
    )

    runAndDecoderScenarios(
      "A runTests override runs a test the filter drops",
      "an AnyFunSuite declaring tests a and b, b tagged samples.Slow, whose runTests also runs b",
      "samples.RunsADroppedTestSuite",
      "the structure holding a, then a's events, then a further structure adding b, before b's events",
      runsADroppedTestSuite,
      arguments = List("-l", "samples.Slow")
    )

    runAndDecoderScenarios(
      "A nested suite's instance that runs registers a different tree from its first structure",
      "a Suites nesting a ConfigMapWrapperSuite of a suite declaring scope 'A config' holding test 'remote' where its " +
        "config map holds db, and test 'local' where it does not",
      "samples.NestsConfigDependentSpec",
      "the structure the empty config map gives the nested suite, the suites' SuiteStarting, then a further structure " +
        "whose root is the nested suite, with its parent and the position the first gave it, before the nested suite's " +
        "scope and test events",
      nestsConfigDependentSpec,
      launch = Launch.ByRunner,
      arguments = List("-Ddb=on"),
      eventLogIsOnlyTheStated = false,
      structures = Some(2)
    )

    runAndDecoderScenarios(
      "An async suite's instance that runs registers a different tree from its first structure",
      "an AsyncFunSpec run through ConfigMapWrapperSuite, declaring scope 'A config' holding test 'remote' where its " +
        "config map holds db, and test 'local' where it does not",
      "samples.AsyncConfigDependentSpec",
      "the structure the empty config map registers, the suite's SuiteStarting, then a further structure of what the " +
        "run's config map registers, before the events of the scope and test it holds",
      asyncConfigDependentSpec,
      arguments = List("-Ddb=on")
    )

    runAndDecoderScenarios(
      "Each test runs in an instance of its own, each registering the same tree",
      "an AnyFunSuite mixing in OneInstancePerTest, declaring tests a and b",
      "samples.OneInstancePerTestSuite",
      "the suite's one structure, then the events of a and b",
      oneInstancePerTestSuite,
      eventLogIsOnlyTheStated = false,
      structures = Some(1)
    )

    runAndDecoderScenarios(
      "A tags override drops a test its registered tags keep",
      "an AnyFunSuite declaring tests a and b, whose tags override tags b with samples.Slow",
      "samples.TagsOverrideSuite",
      "the suite's one structure, holding a alone, then a's events",
      tagsOverrideSuite,
      arguments = List("-l", "samples.Slow"),
      eventLogIsOnlyTheStated = false,
      structures = Some(1)
    )
  }
}
