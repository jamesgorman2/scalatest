package org.scalatest.tools

import org.scalatest.GivenWhenThen
import org.scalatest.events.Event
import org.scalatest.events.EventLogComparison
import org.scalatest.events.RunAborted
import org.scalatest.events.RunCompleted
import org.scalatest.events.SuiteAborted
import org.scalatest.events.SuiteCompleted
import org.scalatest.events.SuiteStarting
import org.scalatest.events.TestEventLogDecoder
import org.scalatest.events.TestFailed
import org.scalatest.events.TestSucceeded
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class TestEventLogStructureSpec extends AnyFeatureSpec with GivenWhenThen with Matchers with TestEventLogRuns {

  import TestEventLogStructureSpec.*

  /**
   * The events a test expects of a run, besides the suite's structure.
   *
   * @param description shown as an And step
   * @param holdsInOrder in the event notation
   */
  private final case class StatedEvents(description: String, holdsInOrder: List[String], mustNotHold: String => Boolean)

  private object StatedEvents {
    val none: StatedEvents = StatedEvents("", Nil, _ => false)
  }

  private def runHolding(structure: StatedStructure, events: StatedEvents): String =
    (List("run started", "run: RunStarting", structure.inNotation) ++ events.holdsInOrder ++ List("run: RunCompleted", "run ended completed")).mkString("\n")

  private def structureLinesOf(lines: Seq[String]): Vector[String] =
    lines.toVector.filter(_.contains(""""item":"structure""""))

  private def isEvent(eventName: String)(line: String): Boolean = line.contains(s""""event":"$eventName"""")

  private def isEventOfTest(eventName: String, testName: String)(line: String): Boolean =
    isEvent(eventName)(line) && line.contains(s"""{"test":"$testName"}""")

  private def structureScenarios(
    scenario: String,
    suite: String,
    arguments: List[String],
    holds: String,
    structure: StatedStructure,
    launch: Launch = Launch.ByFramework,
    events: StatedEvents = StatedEvents.none
  ): Unit = {
    Scenario(scenario) {
      thisProcess
      Given(suite)
      TemporaryEventLog.in { eventLogFile =>
        When(s"${launch.named} runs it with ${("the runner argument" :: arguments).mkString(" and ")}")
        launched(launch, structure.suiteClassName, arguments ++ runnerArgument(eventLogFile))
        val lines = TemporaryEventLog.lines(eventLogFile)

        Then(s"the event log's one structure holds $holds")
        if (events.description.nonEmpty) {
          And(events.description)
        }
        structureLinesOf(lines) shouldBe Vector(structure.line)
        EventLogComparison.mismatchWhenHoldsInOrder(decoded(lines), built(runHolding(structure, events))) shouldBe None
        lines.filter(events.mustNotHold) shouldBe empty
      }
    }

    structureDecoderScenario(scenario, holds, structure)
  }

  private def structureDecoderScenario(scenario: String, holds: String, structure: StatedStructure): Unit = {
    Scenario(s"The decoder reads the structure of: $scenario") {
      When("the decoder is given the line of the suite's structure")
      val event = TestEventLogDecoder.decode(structure.line)

      Then(s"it yields a structure that holds $holds")
      EventLogComparison.mismatchWhenIs(Seq(event), built(structure.inNotation)) shouldBe None
    }
  }

  private val startedTest = """"node":\[[^\n]*\{"test":"([^"]*)"\}\],"executions":\[[0-9,]*\],"event":"TestStarting"""".r

  private def testsInTheOrderTheyStarted(lines: Seq[String]): Vector[String] =
    lines.toVector.flatMap(line => startedTest.findFirstMatchIn(line).map(_.group(1)))

  // A run starts the tests in declared order with a chance of 1 in 6, so all of these runs do with a chance below 1 in 10^23.
  private val runsToStartTheTestsOutOfDeclaredOrder = 30

  private def linesOfARunOutOfDeclaredOrder(structure: StatedStructure, declared: Vector[String]): Vector[String] = {
    Iterator
      .continually(TemporaryEventLog.in { eventLogFile =>
        launched(Launch.ByFramework, structure.suiteClassName, runnerArgument(eventLogFile))
        TemporaryEventLog.lines(eventLogFile)
      })
      .take(runsToStartTheTestsOutOfDeclaredOrder)
      .find(lines => testsInTheOrderTheyStarted(lines) != declared)
      .getOrElse(fail(s"each of $runsToStartTheTestsOutOfDeclaredOrder runs started the tests in declared order"))
  }

  private def thrownBy(throwable: Option[Throwable]): String = throwable.fold("nothing")(thrown => s"${thrown.getClass.getName}: ${thrown.getMessage}")

  private def reported(events: Vector[Event]): Vector[String] = {
    events.collect {
      case aborted: SuiteAborted => s"SuiteAborted ${aborted.suiteName}, thrown ${thrownBy(aborted.throwable)}"
      case aborted: RunAborted => s"RunAborted, thrown ${thrownBy(aborted.throwable)}"
      case shown @ (_: SuiteStarting | _: SuiteCompleted | _: TestSucceeded | _: TestFailed | _: RunCompleted) => ReportedEvents.described(shown)
    }
  }

  private final case class RegistrationThatThrows(
    suite: String,
    suiteClassName: String,
    launch: Launch,
    reports: Vector[String],
    eventLogIs: String
  )

  private val refSpecScopeThrew =
    "org.scalatest.exceptions.NotAllowedException: java.lang.RuntimeException was thrown inside object `a scope`, construction cannot continue."
  private val pathFunSpecScopeThrew =
    "org.scalatest.exceptions.NotAllowedException: " +
      "java.lang.RuntimeException was thrown inside describe(\"b\"), construction cannot continue: \"boom\""
  private val pathFreeSpecScopeThrew =
    "org.scalatest.exceptions.NotAllowedException: " +
      "java.lang.RuntimeException was thrown inside \"b\" -, construction cannot continue: \"boom\""

  // Each suite, the simple name of its sample class, which a sample named Nests<that name> nests, and what its registration throws.
  private val suitesWhoseRegistrationThrows = List(
    ("a RefSpec whose scope object throws as it is registered", "ThrowingScopeRefSpec", refSpecScopeThrew),
    ("a PathAnyFunSpec whose second scope throws as it is registered", "ThrowingSecondScopePathFunSpec", pathFunSpecScopeThrew),
    ("a PathAnyFreeSpec whose second scope throws as it is registered", "ThrowingSecondScopePathFreeSpec", pathFreeSpecScopeThrew)
  )

  // Framework asks a top-level suite for its tests as it builds the suite's SuiteStarting, outside the catch that reports SuiteAborted.
  private val registrationsThatThrowFromFrameworkTasks =
    suitesWhoseRegistrationThrows.map((suite, sampleName, thrown) => (suite, "samples." + sampleName, thrown))

  private val registrationsThatThrow =
    suitesWhoseRegistrationThrows.map { (suite, sampleName, thrown) =>
      RegistrationThatThrows(
        suite,
        "samples." + sampleName,
        Launch.ByRunner,
        Vector(s"RunAborted, thrown $thrown"),
        "run started\nrun: RunAborted\nrun ended aborted"
      )
    } ++ Launch.values.toList.flatMap { launch =>
      suitesWhoseRegistrationThrows.map { (suite, sampleName, thrown) =>
        RegistrationThatThrows(
          s"a suite nesting $suite, and counting none of its tests",
          "samples.Nests" + sampleName,
          launch,
          Vector(s"SuiteStarting Nests$sampleName", s"SuiteAborted Nests$sampleName, thrown $thrown", "RunCompleted"),
          "run started\nrun: RunStarting"
        )
      }
    }

  // Ends the run so that its event log is closed.
  private def thrownByFrameworkTask(suiteClassName: String, arguments: Seq[String]): String = {
    val runner = frameworkRunner(arguments)
    try thrownBy(scala.util.Try(execute(runner, suiteClassName, new RecordingLogger)).failed.toOption)
    finally runner.done()
  }

  /** A scope named s, registered with and labelled `scopeText`, holding tests named t and then u, each given as its test name and label. */
  private def scopeHoldingTests(scopeText: String, testNamesAndLabels: (String, String)*): NodePath => List[String] = { suite =>
    val scope = suite.scope(scopeText)
    TestEventLogLine.scope(scope, 0, "s", scopeText) ::
      testNamesAndLabels.toList.zip(List("t", "u")).zipWithIndex.map { case (((testName, label), name), position) =>
        TestEventLogLine.test(suite.test(testName), scope, position, name, label)
      }
  }

  private final case class LabelledDeclaration(
    styles: List[String],
    declaration: String,
    sampleNamedAfter: String,
    holds: String,
    nodesInNotation: String,
    nodesInFormat: NodePath => List[String]
  )

  private val oneTest = "test \"t\", label \"- t\""
  private val scopeHoldingOneTest = "scope \"s\", label \"s\"\n  test \"t\", label \"- t\""
  private val featureHoldingScenarioWithIcon = "scope \"s\", label \"Feature: s\"\n  test \"t\", label \"- Scenario: t\""

  private val labelledDeclarations = List(
    LabelledDeclaration(
      List("AnyFunSuite", "AsyncFunSuite", "FixtureAnyFunSuite", "FixtureAsyncFunSuite"),
      """test("t")""",
      "WithTest",
      "test t, labelled '- t'",
      oneTest,
      suite => List(TestEventLogLine.test(suite.test("t"), suite, 0, "t", "- t"))
    ),
    LabelledDeclaration(
      List("AnyPropSpec", "FixtureAnyPropSpec"),
      """property("t")""",
      "WithProperty",
      "test t, labelled '- t'",
      oneTest,
      suite => List(TestEventLogLine.test(suite.test("t"), suite, 0, "t", "- t"))
    ),
    LabelledDeclaration(
      List("AnyFunSpec", "AsyncFunSpec", "FixtureAnyFunSpec", "FixtureAsyncFunSpec", "PathAnyFunSpec"),
      """describe("s") { it("t") }""",
      "WithDescribe",
      "scope s, labelled 's', holding test t, labelled '- t'",
      scopeHoldingOneTest,
      scopeHoldingTests("s", "s t" -> "- t")
    ),
    LabelledDeclaration(
      List("AnyFreeSpec", "AsyncFreeSpec", "FixtureAnyFreeSpec", "FixtureAsyncFreeSpec", "PathAnyFreeSpec"),
      """"s" - { "t" in }""",
      "WithDash",
      "scope s, labelled 's', holding test t, labelled '- t'",
      scopeHoldingOneTest,
      scopeHoldingTests("s", "s t" -> "- t")
    ),
    LabelledDeclaration(
      List("AnyWordSpec", "AsyncWordSpec", "FixtureAnyWordSpec", "FixtureAsyncWordSpec"),
      """"s" when { "u" should { "t" in } }""",
      "WithWhenAndShould",
      "scope s, labelled 's', holding scope u, labelled 'when u', holding test t, labelled '- should t'",
      "scope \"s\", label \"s\"\n  scope \"u\", label \"when u\"\n    test \"t\", label \"- should t\"",
      { suite =>
        val scopeS = suite.scope("s")
        val scopeU = scopeS.scope("u")
        List(
          TestEventLogLine.scope(scopeS, 0, "s", "s"),
          TestEventLogLine.scope(scopeU, 0, "u", "when u"),
          TestEventLogLine.test(suite.test("s when u should t"), scopeU, 0, "t", "- should t")
        )
      }
    ),
    LabelledDeclaration(
      List("AnyWordSpec", "AsyncWordSpec", "FixtureAnyWordSpec", "FixtureAsyncWordSpec"),
      """"s" can { "t" in }""",
      "WithCan",
      "scope s, labelled 's', holding test t, labelled '- can t'",
      "scope \"s\", label \"s\"\n  test \"t\", label \"- can t\"",
      scopeHoldingTests("s", "s can t" -> "- can t")
    ),
    LabelledDeclaration(
      List("AnyWordSpec", "AsyncWordSpec", "FixtureAnyWordSpec", "FixtureAsyncWordSpec"),
      """"s" should { "u" which { "t" in }; "v" that { "t" in }; "w" which provides { "t" in }; "x" that provides { "t" in } }, provides being afterWord("provides")""",
      "WithWhichAndThat",
      "scope s, labelled 's', holding scopes u, v, w and x, labelled 'should u which', 'should v that', " +
        "'should w which provides' and 'should x that provides', each holding test t, labelled '- t'",
      """scope "s", label "s"
        |  scope "u", label "should u which"
        |    test "t", label "- t"
        |  scope "v", label "should v that"
        |    test "t", label "- t"
        |  scope "w", label "should w which provides"
        |    test "t", label "- t"
        |  scope "x", label "should x that provides"
        |    test "t", label "- t"""".stripMargin,
      { suite =>
        val scopeS = suite.scope("s")
        TestEventLogLine.scope(scopeS, 0, "s", "s") ::
          List("u which", "v that", "w which provides", "x that provides").zipWithIndex.flatMap { (text, position) =>
            val scope = scopeS.scope(text)
            List(
              TestEventLogLine.scope(scope, position, text.takeWhile(_ != ' '), s"should $text"),
              TestEventLogLine.test(suite.test(s"s should $text t"), scope, 0, "t", "- t")
            )
          }
      }
    ),
    LabelledDeclaration(
      List("AnyWordSpec", "AsyncWordSpec", "FixtureAnyWordSpec", "FixtureAsyncWordSpec"),
      """"u" which { "t" in }; it should { "x" in }; "v" that { "t" in }; they must { "x" in }; "w" which provides { "t" in }; """ +
        """it can { "x" in }; "y" that provides { "t" in }; they should { "x" in }; "z" should { "t" in }; it must { "x" in }; """ +
        """"q" when { "t" in }; they when { "x" in }, provides being afterWord("provides")""",
      "WithShorthandScopes",
      "two scopes for each of u, v, w, y, z and q, the second named and labelled as the first: labelled 'u which', 'v that', " +
        "'w which provides', 'y that provides', 'z' and 'q', the first holding test t and the second test x",
      """scope "u", label "u which"
        |  test "t", label "- t"
        |scope "u", label "u which"
        |  test "x", label "- should x"
        |scope "v", label "v that"
        |  test "t", label "- t"
        |scope "v", label "v that"
        |  test "x", label "- must x"
        |scope "w", label "w which provides"
        |  test "t", label "- t"
        |scope "w", label "w which provides"
        |  test "x", label "- can x"
        |scope "y", label "y that provides"
        |  test "t", label "- t"
        |scope "y", label "y that provides"
        |  test "x", label "- should x"
        |scope "z", label "z"
        |  test "t", label "- should t"
        |scope "z", label "z"
        |  test "x", label "- must x"
        |scope "q", label "q"
        |  test "t", label "- when t"
        |scope "q", label "q"
        |  test "x", label "- when x"""".stripMargin,
      { suite =>
        // A shorthand scope is registered with the text of the scope before it, so each pair shares a text.
        List(
          ("u which", "t", "should x"),
          ("v that", "t", "must x"),
          ("w which provides", "t", "can x"),
          ("y that provides", "t", "should x"),
          ("z", "should t", "must x"),
          ("q", "when t", "when x")
        ).zipWithIndex.flatMap { case ((text, firstTestText, secondTestText), pair) =>
          val name = text.takeWhile(_ != ' ')
          val first = suite.scope(text)
          val second = suite.scope(text, 1)
          List(
            TestEventLogLine.scope(first, 2 * pair, name, text),
            TestEventLogLine.test(suite.test(s"$text $firstTestText"), first, 0, "t", s"- $firstTestText"),
            TestEventLogLine.scope(second, 2 * pair + 1, name, text),
            TestEventLogLine.test(suite.test(s"$text $secondTestText"), second, 0, "x", s"- $secondTestText")
          )
        }
      }
    ),
    LabelledDeclaration(
      List("AnyFlatSpec", "AsyncFlatSpec", "FixtureAnyFlatSpec"),
      """registerTest("can open a file"); registerIgnoredTest("should close it"), or the async style's registerAsyncTest and registerIgnoredAsyncTest""",
      "WithRegisterTest",
      "test 'can open a file', labelled '- can open a file', and test 'should close it', labelled '- should close it' and marked ignored",
      "test \"can open a file\", label \"- can open a file\"\ntest \"should close it\", label \"- should close it\", ignored",
      suite =>
        List(
          TestEventLogLine.test(suite.test("can open a file"), suite, 0, "can open a file", "- can open a file"),
          TestEventLogLine.ignoredTest(suite.test("should close it"), suite, 1, "should close it", "- should close it")
        )
    ),
    // ScalaTest 3.2.20's FixtureAsyncFlatSpec registers a test given to registerIgnoredAsyncTest as one that runs.
    LabelledDeclaration(
      List("FixtureAsyncFlatSpec"),
      """registerAsyncTest("can open a file"); registerIgnoredAsyncTest("should close it")""",
      "WithRegisterTest",
      "test 'can open a file', labelled '- can open a file', and test 'should close it', labelled '- should close it', which runs",
      "test \"can open a file\", label \"- can open a file\"\ntest \"should close it\", label \"- should close it\"",
      suite =>
        List(
          TestEventLogLine.test(suite.test("can open a file"), suite, 0, "can open a file", "- can open a file"),
          TestEventLogLine.test(suite.test("should close it"), suite, 1, "should close it", "- should close it")
        )
    ),
    LabelledDeclaration(
      List("AnyFlatSpec", "AsyncFlatSpec", "FixtureAnyFlatSpec", "FixtureAsyncFlatSpec"),
      """"s" should "t" in; it must "u" in""",
      "WithSubject",
      "scope s, labelled 's', holding test t, labelled '- should t', and test u, labelled '- must u'",
      "scope \"s\", label \"s\"\n  test \"t\", label \"- should t\"\n  test \"u\", label \"- must u\"",
      scopeHoldingTests("s", "s should t" -> "- should t", "s must u" -> "- must u")
    ),
    LabelledDeclaration(
      List("AnyFlatSpec", "AsyncFlatSpec", "FixtureAnyFlatSpec", "FixtureAsyncFlatSpec"),
      """behavior of "s"; it can "t" in""",
      "WithBehaviorOf",
      "scope s, labelled 's', holding test t, labelled '- can t'",
      "scope \"s\", label \"s\"\n  test \"t\", label \"- can t\"",
      scopeHoldingTests("s", "s can t" -> "- can t")
    ),
    LabelledDeclaration(
      List("AnyFeatureSpec", "FixtureAnyFeatureSpec"),
      """Feature("s") { Scenario("t") }""",
      "WithScenario",
      "scope s, labelled 'Feature: s', holding test t, labelled 'Scenario: t'",
      "scope \"s\", label \"Feature: s\"\n  test \"t\", label \"Scenario: t\"",
      scopeHoldingTests("Feature: s", "Feature: s Scenario: t" -> "Scenario: t")
    ),
    LabelledDeclaration(
      List("AsyncFeatureSpec", "FixtureAsyncFeatureSpec"),
      """Feature("s") { Scenario("t") }""",
      "WithScenario",
      "scope s, labelled 'Feature: s', holding test t, labelled '- Scenario: t'",
      featureHoldingScenarioWithIcon,
      scopeHoldingTests("Feature: s", "Feature: s Scenario: t" -> "- Scenario: t")
    ),
    LabelledDeclaration(
      List("AnyFeatureSpec", "AsyncFeatureSpec", "FixtureAnyFeatureSpec", "FixtureAsyncFeatureSpec"),
      """Feature("s") { ignore("t") }""",
      "WithIgnoredScenario",
      "scope s, labelled 'Feature: s', holding test t, labelled '- Scenario: t' and marked ignored",
      featureHoldingScenarioWithIcon + ", ignored",
      { suite =>
        val scope = suite.scope("Feature: s")
        List(
          TestEventLogLine.scope(scope, 0, "s", "Feature: s"),
          TestEventLogLine.ignoredTest(suite.test("Feature: s Scenario: t"), scope, 0, "t", "- Scenario: t")
        )
      }
    ),
    LabelledDeclaration(
      List("RefSpec"),
      "object `s v` { def `t u`: Unit = () }",
      "WithObject",
      "scope 's v', labelled 's v', holding test 't u', labelled '- t u'",
      "scope \"s v\", label \"s v\"\n  test \"t u\", label \"- t u\"",
      { suite =>
        val scope = suite.scope("s v")
        List(TestEventLogLine.scope(scope, 0, "s v", "s v"), TestEventLogLine.test(suite.test("s v t u"), scope, 0, "t u", "- t u"))
      }
    )
  )

  Feature("Each suite's structure") {

    Launch.values.foreach { launch =>
      Scenario(s"A suite ${launch.named} runs has its structure in the event log when it starts to run") {
        thisProcess
        Given("a suite whose one test passes only where the event log held the suite's structure when the suite started to run")
        TemporaryEventLog.in { eventLogFile =>
          When(s"${launch.named} runs it with the runner argument")
          val events =
            eventsReportedWhenLaunched(launch, "samples.ReadsItsStructureSuite", List(s"-DeventLog=$eventLogFile") ++ runnerArgument(eventLogFile))

          Then("the run's reporters are told that the suite started, that its test succeeded and that it completed")
          reported(events) shouldBe Vector(
            "SuiteStarting ReadsItsStructureSuite",
            "TestSucceeded reads its structure",
            "SuiteCompleted ReadsItsStructureSuite",
            "RunCompleted"
          )

          And("the event log holds the suite's structure")
          EventLogComparison.mismatchWhenHoldsInOrder(
            eventLogOf(eventLogFile),
            built(
              """
              structure
                suite "ReadsItsStructureSuite", class "samples.ReadsItsStructureSuite"
                  test "reads its structure", label "- reads its structure"
              """
            )
          ) shouldBe None
        }
      }
    }

    List("sequentially" -> Nil, "in parallel" -> List("-P2")).foreach { (how, parallelArguments) =>
      Scenario(s"A suite the command-line runner discovers and runs $how is a top-level suite") {
        thisProcess
        TemporaryEventLog.in { eventLogFile =>
          Given("a runpath holding one suite, whose one test succeeds")
          withRunpathOfOneSuite(eventLogFile.resolveSibling("runpath")) { runpath =>
            When(s"the command-line runner discovers and runs the runpath's suites $how, with the runner argument")
            runnerRun(List("-R", runpath) ++ parallelArguments ++ runnerArgument(eventLogFile))

            Then("the event log's one structure is the discovered suite's, with no parent, after the discovery suite's SuiteStarting")
            And("no structure holds the discovery suite, whose events are assigned to the run")
            And("the discovered suite's events follow its structure")
            EventLogComparison.mismatchWhenIs(
              eventLogOf(eventLogFile),
              built(
                """
                run started
                run: DiscoveryStarting
                run: DiscoveryCompleted
                run: RunStarting(testCount: 1)
                run: SuiteStarting
                structure
                  suite "OneTestSpec", class "samples.discovered.OneTestSpec"
                    test "succeeds", label "- succeeds"
                suite "OneTestSpec": SuiteStarting
                test "succeeds": TestStarting
                test "succeeds": TestSucceeded
                suite "OneTestSpec": SuiteCompleted
                run: SuiteCompleted
                run: RunCompleted
                run ended completed
                """
              )
            ) shouldBe None
          }
        }
      }
    }

    registrationsThatThrowFromFrameworkTasks.foreach { (suite, suiteClassName, thrown) =>
      Scenario(s"A build tool runs $suite") {
        thisProcess
        Given(suite)
        When("a build tool runs it without the runner argument")
        val thrownWithoutTheArgument = thrownByFrameworkTask(suiteClassName, Nil)

        Then("the suite's task throws what the registration threw, as in ScalaTest 3.2.20")
        thrownWithoutTheArgument shouldBe thrown

        TemporaryEventLog.in { eventLogFile =>
          When("a build tool runs it with the runner argument")
          val thrownWithTheArgument = thrownByFrameworkTask(suiteClassName, runnerArgument(eventLogFile))

          Then("the suite's task throws the same")
          thrownWithTheArgument shouldBe thrown

          And("the event log holds no structure")
          EventLogComparison.mismatchWhenIs(
            eventLogOf(eventLogFile),
            built("run started\nrun: RunStarting\nrun: RunCompleted\nrun ended completed")
          ) shouldBe None
        }
      }
    }

    registrationsThatThrow.foreach { registration =>
      Scenario(s"${registration.launch.named.capitalize} runs ${registration.suite}") {
        thisProcess
        Given(registration.suite)
        When(s"${registration.launch.named} runs it without the runner argument")
        val reportedWithoutTheArgument = reported(eventsReportedWhenLaunched(registration.launch, registration.suiteClassName, Nil))

        Then("the run's reporters are told what ScalaTest 3.2.20 tells them")
        reportedWithoutTheArgument shouldBe registration.reports

        TemporaryEventLog.in { eventLogFile =>
          When(s"${registration.launch.named} runs it with the runner argument")
          eventsReportedWhenLaunched(registration.launch, registration.suiteClassName, runnerArgument(eventLogFile))

          Then("the event log holds no structure of the suite")
          EventLogComparison.mismatchWhenIs(eventLogOf(eventLogFile), built(registration.eventLogIs)) shouldBe None
        }
      }
    }

    structureScenarios(
      "A suite that nests suites",
      "a suite with no tests that nests two suites, each with one test",
      Nil,
      "the suite, and under it each nested suite in the order nested, holding its test",
      StatedStructure(
        "OuterSuites",
        "samples.OuterSuites",
        """suite "Inner1Suite", class "samples.Inner1Suite"
          |  test "inner one", label "- inner one"
          |suite "Inner2Suite", class "samples.Inner2Suite"
          |  test "inner two", label "- inner two"""".stripMargin,
        { suite =>
          val inner1 = suite.suite("samples.Inner1Suite")
          val inner2 = suite.suite("samples.Inner2Suite")
          List(
            TestEventLogLine.nestedSuite(inner1, 0, "Inner1Suite", "samples.Inner1Suite"),
            TestEventLogLine.test(inner1.test("inner one"), inner1, 0, "inner one", "- inner one"),
            TestEventLogLine.nestedSuite(inner2, 1, "Inner2Suite", "samples.Inner2Suite"),
            TestEventLogLine.test(inner2.test("inner two"), inner2, 0, "inner two", "- inner two")
          )
        }
      ),
      // Framework, given a suite by name, runs none of the tests of the suites it nests.
      Launch.ByRunner
    )

    structureScenarios(
      "A suite with a tag excluded and a test ignored",
      "a suite declaring tests fast, slow and later, of which slow is tagged samples.Slow and later is ignored",
      List("-l", "samples.Slow"),
      "fast, and later marked ignored, and not slow",
      StatedStructure(
        "FilteredSuite",
        "samples.FilteredSuite",
        "test \"fast\", label \"- fast\"\ntest \"later\", label \"- later\", ignored",
        suite =>
          List(TestEventLogLine.test(suite.test("fast"), suite, 0, "fast", "- fast"), TestEventLogLine.ignoredTest(suite.test("later"), suite, 1, "later", "- later"))
      ),
      events = StatedEvents("the event log holds `test \"later\": TestIgnored`, and no event is assigned to `slow`", List("test \"later\": TestIgnored"), _.contains("""{"test":"slow"}"""))
    )

    Scenario("A suite whose tests run in random order") {
      thisProcess
      Given("a suite mixing in RandomTestOrder that registers tests a, b and c in that order")
      When("a build tool runs it with the runner argument, and again until its tests start in another order")
      val lines = linesOfARunOutOfDeclaredOrder(shuffledSuite, Vector("a", "b", "c"))

      Then("the event log's one structure holds a, b and c in that order, though the tests ran in another")
      testsInTheOrderTheyStarted(lines).sorted shouldBe Vector("a", "b", "c")
      structureLinesOf(lines) shouldBe Vector(shuffledSuite.line)
      EventLogComparison.mismatchWhenHoldsInOrder(decoded(lines), built(runHolding(shuffledSuite, StatedEvents.none))) shouldBe None
    }

    structureScenarios(
      "A suite with no registration tree",
      "a Suite that overrides testNames as b, a and c, tags c with samples.Slow, and overrides runTest",
      List("-l", "samples.Slow"),
      "b, then a, each labelled with its name after '- ', and not c",
      StatedStructure(
        "PlainSuite",
        "samples.PlainSuite",
        "test \"b\", label \"- b\"\ntest \"a\", label \"- a\"",
        suite => List(TestEventLogLine.test(suite.test("b"), suite, 0, "b", "- b"), TestEventLogLine.test(suite.test("a"), suite, 1, "a", "- a"))
      )
    )
  }

  Feature("Tests marked ignored") {

    structureScenarios(
      "Every test of a suite annotated @Ignore",
      "an AnyFunSuite annotated @Ignore, declaring tests a and b",
      Nil,
      "a and b, each marked ignored",
      StatedStructure(
        "ShelvedSuite",
        "samples.ShelvedSuite",
        "test \"a\", label \"- a\", ignored\ntest \"b\", label \"- b\", ignored",
        suite => List(TestEventLogLine.ignoredTest(suite.test("a"), suite, 0, "a", "- a"), TestEventLogLine.ignoredTest(suite.test("b"), suite, 1, "b", "- b"))
      ),
      events = StatedEvents(
        "the event log holds the TestIgnored of a and of b, and no TestStarting",
        List("test \"a\": TestIgnored", "test \"b\": TestIgnored"),
        isEvent("TestStarting")
      )
    )

    structureScenarios(
      "A test tagged org.scalatest.Ignore in a suite with no registration tree",
      "a Suite that overrides testNames as b, a and c, tags a with org.scalatest.Ignore, and overrides runTest",
      Nil,
      "b, then a marked ignored, then c",
      StatedStructure(
        "IgnoringPlainSuite",
        "samples.IgnoringPlainSuite",
        "test \"b\", label \"- b\"\ntest \"a\", label \"- a\", ignored\ntest \"c\", label \"- c\"",
        suite =>
          List(
            TestEventLogLine.test(suite.test("b"), suite, 0, "b", "- b"),
            TestEventLogLine.ignoredTest(suite.test("a"), suite, 1, "a", "- a"),
            TestEventLogLine.test(suite.test("c"), suite, 2, "c", "- c")
          )
      ),
      events = StatedEvents("the event log holds the TestIgnored of a, and no TestStarting of a", List("test \"a\": TestIgnored"), isEventOfTest("TestStarting", "a"))
    )

    structureScenarios(
      "An ignored test that the filter drops is not in the structure",
      "an AnyFunSuite declaring tests fast and later, of which later is declared with ignore and tagged samples.Slow",
      List("-l", "samples.Slow"),
      "fast alone",
      StatedStructure(
        "IgnoredAndExcludedSuite",
        "samples.IgnoredAndExcludedSuite",
        "test \"fast\", label \"- fast\"",
        suite => List(TestEventLogLine.test(suite.test("fast"), suite, 0, "fast", "- fast"))
      ),
      events = StatedEvents("the event log holds no TestIgnored", Nil, isEvent("TestIgnored"))
    )
  }

  Feature("Labels") {

    for (declared <- labelledDeclarations; style <- declared.styles) {
      structureScenarios(
        s"${style} declaring ${declared.declaration}",
        s"a suite of ${style}, declaring ${declared.declaration}",
        Nil,
        s"below the suite, ${declared.holds}",
        StatedStructure(
          style + declared.sampleNamedAfter,
          "samples.labels." + style + declared.sampleNamedAfter,
          declared.nodesInNotation,
          declared.nodesInFormat
        )
      )
    }
  }

  Feature("A suite's class name") {

    structureScenarios(
      "A suite's constructor throws",
      "a suite class whose constructor throws",
      Nil,
      "the suite alone, with the class that failed to construct",
      StatedStructure("ThrowingConstructorSpec", "samples.ThrowingConstructorSpec", "", _ => Nil)
    )
  }
}

private[tools] object TestEventLogStructureSpec {

  /**
   * @param nodesInNotation the nodes below the suite, the outermost unindented
   * @param nodesInFormat the nodes below the suite, in the order the event holds them, given the suite's node path
   */
  final case class StatedStructure(
    suiteName: String,
    suiteClassName: String,
    nodesInNotation: String,
    nodesInFormat: NodePath => List[String]
  ) {

    def inNotation: String =
      ("structure" :: s"""  suite "$suiteName", class "$suiteClassName"""" :: nodesInNotation.linesIterator.map("    " + _).toList).mkString("\n")

    def line: String = TestEventLogLine.structure((TestEventLogLine.topLevelSuite(suiteName, suiteClassName) :: nodesInFormat(NodePath.topLevel(suiteClassName)))*)
  }

  val shuffledSuite: StatedStructure = StatedStructure(
    "ShuffledSuite",
    "samples.ShuffledSuite",
    "test \"a\", label \"- a\"\ntest \"b\", label \"- b\"\ntest \"c\", label \"- c\"",
    suite => List("a", "b", "c").zipWithIndex.map((name, position) => TestEventLogLine.test(suite.test(name), suite, position, name, s"- $name"))
  )
}

final class TestEventLogStructureDecoderSpec extends AnyWordSpec with Matchers with TestEventLogRuns {

  import TestEventLogStructureSpec.*

  "The decoder, given the line of the structure of a suite whose tests run in random order" should {
    "yield a structure that holds a, b and c in that order, whatever order the tests ran in" in {
      val event = TestEventLogDecoder.decode(shuffledSuite.line)
      EventLogComparison.mismatchWhenIs(Seq(event), built(shuffledSuite.inNotation)) shouldBe None
    }
  }
}
