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

final class TestEventLogStructureSpec extends AnyFeatureSpec with GivenWhenThen with Matchers with TestEventLogRuns {

  /**
   * A suite's structure as a criterion states it, in the event notation, and as the event log's format writes it.
   *
   * @param nodesInNotation the nodes below the suite, the outermost unindented
   * @param nodesInFormat the nodes below the suite, in the order the event holds them, the suite's node ID being 1
   */
  private final case class StatedStructure(
    suiteName: String,
    suiteClassName: String,
    nodesInNotation: String,
    nodesInFormat: List[String]
  ) {

    def inNotation: String =
      ("structure" :: s"""  suite "$suiteName", class "$suiteClassName"""" :: nodesInNotation.linesIterator.map("    " + _).toList).mkString("\n")

    def line: String = TestEventLogLine.structure((TestEventLogLine.suite(1, suiteName, suiteClassName) :: nodesInFormat)*)
  }

  private def runHolding(structure: StatedStructure): String =
    List("run started", "run: RunStarting", structure.inNotation, "run: RunCompleted", "run ended completed").mkString("\n")

  private def structureLinesOf(lines: Seq[String]): Vector[String] =
    lines.toVector.filter(_.contains(""""item":"structure"""")).map(TestEventLogLine.withNodesRenumbered)

  /** Registers the scenario of the run that writes a suite's structure, and the scenario of the decoder that reads it. */
  private def structureScenarios(
    scenario: String,
    suite: String,
    arguments: List[String],
    holds: String,
    structure: StatedStructure,
    launch: Launch = Launch.ByFramework
  ): Unit = {
    Scenario(scenario) {
      thisProcess
      Given(suite)
      TemporaryEventLog.in { eventLogFile =>
        When(s"${launch.named} runs it with ${("the runner argument" :: arguments).mkString(" and ")}")
        launched(launch, structure.suiteClassName, arguments ++ runnerArgument(eventLogFile))
        val lines = TemporaryEventLog.lines(eventLogFile)

        Then(s"the event log's one structure holds $holds")
        structureLinesOf(lines) shouldBe Vector(structure.line)
        EventLogComparison.mismatchWhenIs(decodedOrFail(lines), built(runHolding(structure))) shouldBe None
      }
    }

    Scenario(s"The decoder reads the structure of: $scenario") {
      When("the decoder is given the line of the suite's structure")
      val event = TestEventLogDecoder.decode(structure.line)

      Then(s"it yields a structure that holds $holds")
      EventLogComparison.mismatchWhenIs(event.toSeq, built(structure.inNotation)) shouldBe None
    }
  }

  private def thrownBy(throwable: Option[Throwable]): String = throwable.fold("nothing")(thrown => s"${thrown.getClass.getName}: ${thrown.getMessage}")

  /** How each suite and test of a run ended, and how the run did, as its reporters were told, in the order they were told. */
  private def reported(events: Vector[Event]): Vector[String] = {
    events.collect {
      case starting: SuiteStarting => s"SuiteStarting ${starting.suiteName}"
      case completed: SuiteCompleted => s"SuiteCompleted ${completed.suiteName}"
      case aborted: SuiteAborted => s"SuiteAborted ${aborted.suiteName}, thrown ${thrownBy(aborted.throwable)}"
      case succeeded: TestSucceeded => s"TestSucceeded ${succeeded.testName}"
      case failed: TestFailed => s"TestFailed ${failed.testName}"
      case _: RunCompleted => "RunCompleted"
      case aborted: RunAborted => s"RunAborted, thrown ${thrownBy(aborted.throwable)}"
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

  // Returns what the suite's task throws, and ends the run so that its event log is closed.
  private def thrownByFrameworkTask(suiteClassName: String, arguments: Seq[String]): String = {
    val runner = frameworkRunner(arguments)
    try thrownBy(scala.util.Try(execute(runner, suiteClassName, new RecordingLogger)).failed.toOption)
    finally runner.done()
  }

  private def scopeHoldingTests(scopeLabel: String, testLabels: String*): List[String] =
    TestEventLogLine.scope(2, 1, 0, "s", scopeLabel) ::
      testLabels.toList.zip(List("t", "u")).zipWithIndex.map { case ((label, name), position) =>
        TestEventLogLine.test(3 + position, 2, position, name, label)
      }

  private final case class LabelledDeclaration(
    styles: List[String],
    declaration: String,
    sampleNamedAfter: String,
    holds: String,
    nodesInNotation: String,
    nodesInFormat: List[String]
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
      List(TestEventLogLine.test(2, 1, 0, "t", "- t"))
    ),
    LabelledDeclaration(
      List("AnyPropSpec", "FixtureAnyPropSpec"),
      """property("t")""",
      "WithProperty",
      "test t, labelled '- t'",
      oneTest,
      List(TestEventLogLine.test(2, 1, 0, "t", "- t"))
    ),
    LabelledDeclaration(
      List("AnyFunSpec", "AsyncFunSpec", "FixtureAnyFunSpec", "FixtureAsyncFunSpec", "PathAnyFunSpec"),
      """describe("s") { it("t") }""",
      "WithDescribe",
      "scope s, labelled 's', holding test t, labelled '- t'",
      scopeHoldingOneTest,
      scopeHoldingTests("s", "- t")
    ),
    LabelledDeclaration(
      List("AnyFreeSpec", "AsyncFreeSpec", "FixtureAnyFreeSpec", "FixtureAsyncFreeSpec", "PathAnyFreeSpec"),
      """"s" - { "t" in }""",
      "WithDash",
      "scope s, labelled 's', holding test t, labelled '- t'",
      scopeHoldingOneTest,
      scopeHoldingTests("s", "- t")
    ),
    LabelledDeclaration(
      List("AnyWordSpec", "AsyncWordSpec", "FixtureAnyWordSpec", "FixtureAsyncWordSpec"),
      """"s" when { "u" should { "t" in } }""",
      "WithWhenAndShould",
      "scope s, labelled 's', holding scope u, labelled 'when u', holding test t, labelled '- should t'",
      "scope \"s\", label \"s\"\n  scope \"u\", label \"when u\"\n    test \"t\", label \"- should t\"",
      List(
        TestEventLogLine.scope(2, 1, 0, "s", "s"),
        TestEventLogLine.scope(3, 2, 0, "u", "when u"),
        TestEventLogLine.test(4, 3, 0, "t", "- should t")
      )
    ),
    LabelledDeclaration(
      List("AnyWordSpec", "AsyncWordSpec", "FixtureAnyWordSpec", "FixtureAsyncWordSpec"),
      """"s" can { "t" in }""",
      "WithCan",
      "scope s, labelled 's', holding test t, labelled '- can t'",
      "scope \"s\", label \"s\"\n  test \"t\", label \"- can t\"",
      scopeHoldingTests("s", "- can t")
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
      List(
        TestEventLogLine.scope(2, 1, 0, "s", "s"),
        TestEventLogLine.scope(3, 2, 0, "u", "should u which"),
        TestEventLogLine.test(4, 3, 0, "t", "- t"),
        TestEventLogLine.scope(5, 2, 1, "v", "should v that"),
        TestEventLogLine.test(6, 5, 0, "t", "- t"),
        TestEventLogLine.scope(7, 2, 2, "w", "should w which provides"),
        TestEventLogLine.test(8, 7, 0, "t", "- t"),
        TestEventLogLine.scope(9, 2, 3, "x", "should x that provides"),
        TestEventLogLine.test(10, 9, 0, "t", "- t")
      )
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
      List(
        TestEventLogLine.scope(2, 1, 0, "u", "u which"),
        TestEventLogLine.test(3, 2, 0, "t", "- t"),
        TestEventLogLine.scope(4, 1, 1, "u", "u which"),
        TestEventLogLine.test(5, 4, 0, "x", "- should x"),
        TestEventLogLine.scope(6, 1, 2, "v", "v that"),
        TestEventLogLine.test(7, 6, 0, "t", "- t"),
        TestEventLogLine.scope(8, 1, 3, "v", "v that"),
        TestEventLogLine.test(9, 8, 0, "x", "- must x"),
        TestEventLogLine.scope(10, 1, 4, "w", "w which provides"),
        TestEventLogLine.test(11, 10, 0, "t", "- t"),
        TestEventLogLine.scope(12, 1, 5, "w", "w which provides"),
        TestEventLogLine.test(13, 12, 0, "x", "- can x"),
        TestEventLogLine.scope(14, 1, 6, "y", "y that provides"),
        TestEventLogLine.test(15, 14, 0, "t", "- t"),
        TestEventLogLine.scope(16, 1, 7, "y", "y that provides"),
        TestEventLogLine.test(17, 16, 0, "x", "- should x"),
        TestEventLogLine.scope(18, 1, 8, "z", "z"),
        TestEventLogLine.test(19, 18, 0, "t", "- should t"),
        TestEventLogLine.scope(20, 1, 9, "z", "z"),
        TestEventLogLine.test(21, 20, 0, "x", "- must x"),
        TestEventLogLine.scope(22, 1, 10, "q", "q"),
        TestEventLogLine.test(23, 22, 0, "t", "- when t"),
        TestEventLogLine.scope(24, 1, 11, "q", "q"),
        TestEventLogLine.test(25, 24, 0, "x", "- when x")
      )
    ),
    LabelledDeclaration(
      List("AnyFlatSpec", "AsyncFlatSpec", "FixtureAnyFlatSpec", "FixtureAsyncFlatSpec"),
      """registerTest("can open a file"); registerIgnoredTest("should close it"), or the async style's registerAsyncTest and registerIgnoredAsyncTest""",
      "WithRegisterTest",
      "test 'can open a file', labelled '- can open a file', and test 'should close it', labelled '- should close it'",
      "test \"can open a file\", label \"- can open a file\"\ntest \"should close it\", label \"- should close it\"",
      List(
        TestEventLogLine.test(2, 1, 0, "can open a file", "- can open a file"),
        TestEventLogLine.test(3, 1, 1, "should close it", "- should close it")
      )
    ),
    LabelledDeclaration(
      List("AnyFlatSpec", "AsyncFlatSpec", "FixtureAnyFlatSpec", "FixtureAsyncFlatSpec"),
      """"s" should "t" in; it must "u" in""",
      "WithSubject",
      "scope s, labelled 's', holding test t, labelled '- should t', and test u, labelled '- must u'",
      "scope \"s\", label \"s\"\n  test \"t\", label \"- should t\"\n  test \"u\", label \"- must u\"",
      scopeHoldingTests("s", "- should t", "- must u")
    ),
    LabelledDeclaration(
      List("AnyFlatSpec", "AsyncFlatSpec", "FixtureAnyFlatSpec", "FixtureAsyncFlatSpec"),
      """behavior of "s"; it can "t" in""",
      "WithBehaviorOf",
      "scope s, labelled 's', holding test t, labelled '- can t'",
      "scope \"s\", label \"s\"\n  test \"t\", label \"- can t\"",
      scopeHoldingTests("s", "- can t")
    ),
    LabelledDeclaration(
      List("AnyFeatureSpec", "FixtureAnyFeatureSpec"),
      """Feature("s") { Scenario("t") }""",
      "WithScenario",
      "scope s, labelled 'Feature: s', holding test t, labelled 'Scenario: t'",
      "scope \"s\", label \"Feature: s\"\n  test \"t\", label \"Scenario: t\"",
      scopeHoldingTests("Feature: s", "Scenario: t")
    ),
    LabelledDeclaration(
      List("AsyncFeatureSpec", "FixtureAsyncFeatureSpec"),
      """Feature("s") { Scenario("t") }""",
      "WithScenario",
      "scope s, labelled 'Feature: s', holding test t, labelled '- Scenario: t'",
      featureHoldingScenarioWithIcon,
      scopeHoldingTests("Feature: s", "- Scenario: t")
    ),
    LabelledDeclaration(
      List("AnyFeatureSpec", "AsyncFeatureSpec", "FixtureAnyFeatureSpec", "FixtureAsyncFeatureSpec"),
      """Feature("s") { ignore("t") }""",
      "WithIgnoredScenario",
      "scope s, labelled 'Feature: s', holding test t, labelled '- Scenario: t'",
      featureHoldingScenarioWithIcon,
      scopeHoldingTests("Feature: s", "- Scenario: t")
    ),
    LabelledDeclaration(
      List("RefSpec"),
      "object `s v` { def `t u`: Unit = () }",
      "WithObject",
      "scope 's v', labelled 's v', holding test 't u', labelled '- t u'",
      "scope \"s v\", label \"s v\"\n  test \"t u\", label \"- t u\"",
      List(TestEventLogLine.scope(2, 1, 0, "s v", "s v"), TestEventLogLine.test(3, 2, 0, "t u", "- t u"))
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
      Scenario(s"A suite Runner.run discovers and runs $how is a top-level suite") {
        thisProcess
        TemporaryEventLog.in { eventLogFile =>
          Given("a runpath holding one suite, whose one test succeeds")
          withRunpathOfOneSuite(eventLogFile.resolveSibling("runpath")) { runpath =>
            When(s"Runner.run discovers and runs the runpath's suites $how, with the runner argument")
            runnerRun(List("-R", runpath) ++ parallelArguments ++ runnerArgument(eventLogFile))

            Then("the event log's one structure is the discovered suite's, with no parent, after the discovery suite's SuiteStarting")
            And("no structure holds the discovery suite, whose events are assigned to the run")
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
      Scenario(s"Framework runs $suite") {
        thisProcess
        Given(suite)
        When("Framework runs it without the runner argument")
        val thrownWithoutTheArgument = thrownByFrameworkTask(suiteClassName, Nil)

        Then("the suite's task throws what the registration threw, as in ScalaTest 3.2.20")
        thrownWithoutTheArgument shouldBe thrown

        TemporaryEventLog.in { eventLogFile =>
          When("Framework runs it with the runner argument")
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
      Scenario(s"${registration.launch.named} runs ${registration.suite}") {
        thisProcess
        Given(registration.suite)
        When(s"${registration.launch.named} runs it without the runner argument")
        val reportedWithoutTheArgument = reported(eventsReportedWhenLaunched(registration.launch, registration.suiteClassName, Nil))

        Then("the run's reporters are told what ScalaTest 3.2.20 tells them")
        reportedWithoutTheArgument shouldBe registration.reports

        TemporaryEventLog.in { eventLogFile =>
          When(s"${registration.launch.named} runs it with the runner argument")
          val reportedWithTheArgument =
            reported(eventsReportedWhenLaunched(registration.launch, registration.suiteClassName, runnerArgument(eventLogFile)))

          Then("the run's reporters are told the same")
          reportedWithTheArgument shouldBe registration.reports

          And("the event log holds no structure of the suite")
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
        List(
          TestEventLogLine.nestedSuite(2, 1, 0, "Inner1Suite", "samples.Inner1Suite"),
          TestEventLogLine.test(3, 2, 0, "inner one", "- inner one"),
          TestEventLogLine.nestedSuite(4, 1, 1, "Inner2Suite", "samples.Inner2Suite"),
          TestEventLogLine.test(5, 4, 0, "inner two", "- inner two")
        )
      ),
      // Framework, given a suite by name, runs none of the tests of the suites it nests.
      Launch.ByRunner
    )

    structureScenarios(
      "A suite with a tag excluded and a test ignored",
      "a suite declaring tests fast, slow and later, of which slow is tagged samples.Slow and later is ignored",
      List("-l", "samples.Slow"),
      "fast and later, and not slow",
      StatedStructure(
        "FilteredSuite",
        "samples.FilteredSuite",
        "test \"fast\", label \"- fast\"\ntest \"later\", label \"- later\"",
        List(TestEventLogLine.test(2, 1, 0, "fast", "- fast"), TestEventLogLine.test(3, 1, 1, "later", "- later"))
      )
    )

    structureScenarios(
      "A suite whose tests run in random order",
      "a suite mixing in RandomTestOrder that registers tests a, b and c in that order",
      Nil,
      "a, b and c in the order registered",
      StatedStructure(
        "ShuffledSuite",
        "samples.ShuffledSuite",
        "test \"a\", label \"- a\"\ntest \"b\", label \"- b\"\ntest \"c\", label \"- c\"",
        List(
          TestEventLogLine.test(2, 1, 0, "a", "- a"),
          TestEventLogLine.test(3, 1, 1, "b", "- b"),
          TestEventLogLine.test(4, 1, 2, "c", "- c")
        )
      )
    )

    structureScenarios(
      "A suite with no registration tree",
      "a Suite that overrides testNames as b, a and c, tags c with samples.Slow, and overrides runTest",
      List("-l", "samples.Slow"),
      "b, then a, each labelled with its name after '- ', and not c",
      StatedStructure(
        "PlainSuite",
        "samples.PlainSuite",
        "test \"b\", label \"- b\"\ntest \"a\", label \"- a\"",
        List(TestEventLogLine.test(2, 1, 0, "b", "- b"), TestEventLogLine.test(3, 1, 1, "a", "- a"))
      )
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
      "A suite whose constructor throws",
      "a suite class whose constructor throws",
      Nil,
      "the suite alone, with the class that failed to construct",
      StatedStructure("BrokenCtorSpec", "samples.BrokenCtorSpec", "", Nil)
    )
  }
}
