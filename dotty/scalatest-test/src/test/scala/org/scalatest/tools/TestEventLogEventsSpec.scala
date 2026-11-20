package org.scalatest.tools

import java.nio.file.Paths
import org.scalatest.GivenWhenThen
import org.scalatest.events.AlertProvided
import org.scalatest.events.DecodedEvent
import org.scalatest.events.DecodedThrowable
import org.scalatest.events.Event
import org.scalatest.events.EventLogComparison
import org.scalatest.events.InfoProvided
import org.scalatest.events.MarkupProvided
import org.scalatest.events.NoteProvided
import org.scalatest.events.RunCompleted
import org.scalatest.events.RunStarting
import org.scalatest.events.SuiteCompleted
import org.scalatest.events.SuiteStarting
import org.scalatest.events.TestEventLogEvent
import org.scalatest.events.TestFailed
import org.scalatest.events.TestStarting
import org.scalatest.events.TestSucceeded
import org.scalatest.exceptions.StackDepth
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import samples.MessageCallTimes
import samples.ReportsText

final class TestEventLogEventsSpec extends AnyFeatureSpec with GivenWhenThen with Matchers with TestEventLogRuns {

  import TestEventLogEventsSpec.*

  /** As both a JSON string and the event notation write it. */
  private def escaped(text: String): String = {
    val backslash = 92.toChar.toString
    text.flatMap {
      case '\n' => backslash + "n"
      case '\r' => backslash + "r"
      case '\t' => backslash + "t"
      case '"' => backslash + "\""
      case c if c == 92.toChar => backslash + backslash
      case c if c < ' ' || c > '~' => backslash + "u%04X".format(c.toInt)
      case c => c.toString
    }
  }

  private final case class RunWithTheArgument(reported: Vector[Event], eventLog: Vector[TestEventLogEvent], messageCallTimes: Vector[(Long, Long)])

  private def testFailedOf(reported: Vector[Event]): TestFailed =
    reported.collectFirst { case failed: TestFailed => failed }.getOrElse(fail("the reporters were told of no TestFailed"))

  private def decodedEventsIn(log: Vector[TestEventLogEvent]): Vector[DecodedEvent] =
    log.collect { case TestEventLogEvent.ScalaTestEvent(_, event) => event }

  private def throwableOfTestFailed(log: Vector[TestEventLogEvent]): DecodedThrowable =
    decodedEventsIn(log).collectFirst { case failed: DecodedEvent.TestFailed => failed.throwable }.flatten.getOrElse(fail("no TestFailed holds a throwable"))

  /** Registers two scenarios: the run writing the event log, and the decoder reading it. */
  private def eventScenarios(
    scenario: String,
    suite: String,
    suiteClassName: String,
    eventLogHolds: String,
    stated: StatedRun,
    furtherChecksOfRun: RunWithTheArgument => Unit = _ => ()
  ): Unit = {
    Scenario(scenario) {
      thisProcess
      Given(suite)
      When("a build tool runs it without the runner argument")
      val reportedWithoutTheArgument = eventsReportedWhenLaunched(Launch.ByFramework, suiteClassName, Nil)

      Then("the run's reporters are told what ScalaTest 3.2.20 tells them")
      ReportedEvents.of(reportedWithoutTheArgument) shouldBe stated.reported

      TemporaryEventLog.in { eventLogFile =>
        When("a build tool runs it with the runner argument")
        val (reportedWithTheArgument, messageCallTimes) =
          MessageCallTimes.recordedIn(eventsReportedWhenLaunched(Launch.ByFramework, suiteClassName, runnerArgument(eventLogFile)))
        val lines = TemporaryEventLog.lines(eventLogFile)

        Then(s"the event log is $eventLogHolds")
        val log = decoded(lines)
        StatedLines.mismatchWhenIs(lines, stated.eventLogInFormat) shouldBe None
        EventLogComparison.mismatchWhenIs(log, built(stated.eventLogInNotation)) shouldBe None
        furtherChecksOfRun(RunWithTheArgument(reportedWithTheArgument, log, messageCallTimes))
      }
    }

    Scenario(s"The decoder reads the event log of: $scenario") {
      When("the decoder is given the lines of that event log")
      val events = decoded(stated.linesTheDecoderReads.getOrElse(stated.eventLogInFormat))

      Then(s"it yields $eventLogHolds")
      EventLogComparison.mismatchWhenIs(events, built(stated.eventLogInNotation)) shouldBe None
    }
  }

  private val messagesInOrderOfCall = List(
    "InfoProvided" -> "InfoProvided(message: \"checked size\")" -> """"message":"checked size",""",
    "NoteProvided" -> "NoteProvided(message: \"a note\")" -> """"message":"a note",""",
    "AlertProvided" -> "AlertProvided(message: \"an alert\")" -> """"message":"an alert",""",
    "MarkupProvided" -> "MarkupProvided(text: \"*markup*\")" -> """"text":"*markup*","""
  )

  private def messagesSpec(suiteClassName: String, reportedMessagesAndClose: Vector[String], fails: Boolean): StatedRun = {
    val (closingName, closingFields) = if (fails) ("TestFailed", """"message":"not empty",""") else ("TestSucceeded", "")
    StatedRun(
      Vector("RunStarting", "SuiteStarting MessagesSpec", "TestStarting is empty") ++ reportedMessagesAndClose ++
        Vector("SuiteCompleted MessagesSpec", "RunCompleted"),
      notationOfOneTest(
        "MessagesSpec",
        suiteClassName,
        "is empty",
        ("TestStarting" :: messagesInOrderOfCall.map { case ((_, notation), _) => notation }) :+ closingName*
      ),
      frameworkRunOfOneTest(
        "MessagesSpec",
        suiteClassName,
        "is empty",
        (ofTest("TestStarting") :: messagesInOrderOfCall.map { case ((name, _), fields) => ofTest(name, fields) }) :+ ofTest(closingName, closingFields)*
      )
    )
  }

  private val recordingInfoAndMarkup = ", recording InfoProvided checked size, MarkupProvided *markup*"

  private def reportedByAnyFunSpec(fails: Boolean): Vector[String] = {
    val closing = if (fails) s"TestFailed is empty: not empty$recordingInfoAndMarkup" else s"TestSucceeded is empty$recordingInfoAndMarkup"
    Vector("NoteProvided a note", "AlertProvided an alert", closing)
  }

  // 3.2.20 passes on only a path-style test's infos, and only inside its closing event.
  private def reportedByPathStyleSpec(fails: Boolean): Vector[String] = {
    Vector(if (fails) "TestFailed is empty: not empty, recording InfoProvided checked size" else "TestSucceeded is empty, recording InfoProvided checked size")
  }

  private def messageTimeStampsArePlacedInTheirCalls(run: RunWithTheArgument): Unit = {
    And("each message's timestamp is the time the test called it")
    val timeStamps = decodedEventsIn(run.eventLog).collect {
      case info: DecodedEvent.InfoProvided => info.timeStamp
      case note: DecodedEvent.NoteProvided => note.timeStamp
      case alert: DecodedEvent.AlertProvided => alert.timeStamp
      case markup: DecodedEvent.MarkupProvided => markup.timeStamp
    }
    val callTimes = run.messageCallTimes
    callTimes should have size 4
    timeStamps.zip(callTimes).filterNot { case (timeStamp, (before, after)) => before <= timeStamp && timeStamp <= after } shouldBe Vector.empty
  }

  Feature("Messages") {

    val rows = List(
      ("an AnyFunSpec", "succeeds", "samples.SucceedingMessagesFunSpec", reportedByAnyFunSpec(fails = false), false),
      ("an AnyFunSpec", "fails", "samples.FailingMessagesFunSpec", reportedByAnyFunSpec(fails = true), true),
      (
        "an AnyFunSpec that overrides runTest to pass super.runTest its args with args.reporter wrapped in a reporter that forwards each event",
        "succeeds",
        "samples.ForwardingMessagesFunSpec",
        reportedByAnyFunSpec(fails = false),
        false
      ),
      ("a PathAnyFunSpec", "succeeds", "samples.SucceedingMessagesPathFunSpec", reportedByPathStyleSpec(fails = false), false),
      ("a PathAnyFunSpec", "fails", "samples.FailingMessagesPathFunSpec", reportedByPathStyleSpec(fails = true), true),
      ("a PathAnyFreeSpec", "succeeds", "samples.SucceedingMessagesPathFreeSpec", reportedByPathStyleSpec(fails = false), false),
      ("a PathAnyFreeSpec", "fails", "samples.FailingMessagesPathFreeSpec", reportedByPathStyleSpec(fails = true), true)
    )

    rows.foreach { (style, outcome, suiteClassName, reportedMessagesAndClose, fails) =>
      eventScenarios(
        s"MessagesSpec, $style: a test that $outcome",
        s"$style whose test is empty calls info, note, alert and markup in that order, then $outcome",
        suiteClassName,
        "the test's start, its four messages in the order the test called them and its close, and no other message assigned to the test",
        messagesSpec(suiteClassName, reportedMessagesAndClose, fails),
        messageTimeStampsArePlacedInTheirCalls
      )
    }
  }

  Feature("What the event log keeps of each event") {

    val texts = List(
      "a text of two lines" -> "samples.TwoLinesTextSuite",
      "a text beyond ASCII" -> "samples.NonAsciiTextSuite",
      "a text holding a lone surrogate" -> "samples.LoneSurrogateTextSuite",
      "a text holding a tab and a carriage return" -> "samples.ControlCharactersTextSuite",
      "an empty text" -> "samples.EmptyTextSuite"
    )

    texts.foreach { (description, suiteClassName) =>
      val text = Class.forName(suiteClassName).getDeclaredConstructor().newInstance().asInstanceOf[ReportsText].text
      val inFormat = s""""message":"${escaped(text)}","""
      eventScenarios(
        s"Text: $description",
        s"an AnyFunSuite whose test reports calls info with $description, then fails with it",
        suiteClassName,
        "the test's info and its failure, each with the text exactly as the test passed it",
        StatedRun(
          Vector(
            "RunStarting",
            "SuiteStarting TextSuite",
            "TestStarting reports",
            s"TestFailed reports: $text, recording InfoProvided $text",
            "SuiteCompleted TextSuite",
            "RunCompleted"
          ),
          notationOfOneTest(
            "TextSuite",
            suiteClassName,
            "reports",
            "TestStarting",
            s"InfoProvided(message: \"${escaped(text)}\")",
            s"TestFailed(message: \"${escaped(text)}\")"
          ),
          frameworkRunOfOneTest("TextSuite", suiteClassName, "reports", ofTest("TestStarting"), ofTest("InfoProvided", inFormat), ofTest("TestFailed", inFormat))
        )
      )
    }

    val throwSuiteThrown =
      """java.lang.IllegalStateException "outer" suppressing [java.lang.RuntimeException "closing"] caused by java.io.IOException "inner""""

    eventScenarios(
      "A cause chain with a suppressed throwable",
      "an AnyFunSuite whose test throws an IllegalStateException whose cause is an IOException and which suppresses a RuntimeException",
      "samples.ThrowSuite",
      "the test's failure, holding the thrown throwable, its suppressed throwable and its cause",
      throwSuite(
        throwSuiteThrown,
        """{"id":1,"className":"java.lang.IllegalStateException","message":"outer","""
          + """"cause":{"id":2,"className":"java.io.IOException","message":"inner"},"""
          + """"suppressed":[{"id":3,"className":"java.lang.RuntimeException","message":"closing"}]}"""
      ),
      { run =>
        And("each of the three throwables holds every frame of the corresponding thrown object, each frame with the text StackTraceElement.toString gives it")
        val thrown = testFailedOf(run.reported).throwable.getOrElse(fail("the TestFailed holds no throwable"))
        val decoded = throwableOfTestFailed(run.eventLog)
        def framesOf(throwable: Throwable): Vector[String] = throwable.getStackTrace.toVector.map(_.toString)
        decoded.frames shouldBe framesOf(thrown)
        decoded.suppressed.map(_.frames) shouldBe Vector(framesOf(thrown.getSuppressed.head))
        decoded.cause.map(_.frames) shouldBe Some(framesOf(thrown.getCause))
      }
    )

    def failSuite(
      suiteClassName: String,
      throwableInNotation: String,
      testFailedInFormat: String,
      testFailedTheDecoderReads: String,
      reportedFailure: String
    ): StatedRun = {
      def run(testFailed: String): List[String] =
        frameworkRunOfOneTest("FailSuite", suiteClassName, "t", ofTest("TestStarting"), ofTest("TestFailed", testFailed))
      StatedRun(
        Vector("RunStarting", "SuiteStarting FailSuite", "TestStarting t", s"TestFailed t: $reportedFailure", "SuiteCompleted FailSuite", "RunCompleted"),
        notationOfOneTest("FailSuite", suiteClassName, "t", "TestStarting", s"TestFailed($throwableInNotation)"),
        run(testFailedInFormat),
        linesTheDecoderReads = Some(run(testFailedTheDecoderReads))
      )
    }

    // What Scalactic gives as a pathname in code compiled without SCALACTIC_FILL_FILE_PATHNAMES=yes.
    val pathnameUnfilled = "Please set the environment variable SCALACTIC_FILL_FILE_PATHNAMES to yes at compile time to enable this feature."

    eventScenarios(
      "A StackDepth exception",
      "an AnyFunSuite in file FailSuite.scala whose test t calls fail(\"boom\") at line 7",
      "samples.FailSuite",
      "the test's failure, holding a TestFailedException that gives the failed code's file, pathname and line",
      failSuite(
        "samples.FailSuite",
        """throwable: org.scalatest.exceptions.TestFailedException "boom" at FailSuite.scala:7""",
        """"throwable":{"id":1,"className":"org.scalatest.exceptions.TestFailedException","message":"boom","stackDepth":{"fileName":"FailSuite.scala","lineNumber":7}},""",
        """"message":"boom","throwable":{"id":1,"className":"org.scalatest.exceptions.TestFailedException","message":"boom","stackDepth":{"fileName":"FailSuite.scala","filePathname":"/work/samples/FailSuite.scala","lineNumber":7}},""",
        "boom"
      ),
      { run =>
        And("the throwable's file pathname is the pathname of samples/FailSuite.scala that the run's reporters are told")
        val toldPathname = testFailedOf(run.reported).throwable.collect { case failed: StackDepth => failed.failedCodeFilePathname }.flatten
        toldPathname should not be empty
        throwableOfTestFailed(run.eventLog).stackDepth.flatMap(_.filePathname) shouldBe toldPathname
        toldPathname.foreach { pathname =>
          if (pathname == pathnameUnfilled) {
            info("FailSuite.scala was compiled without SCALACTIC_FILL_FILE_PATHNAMES=yes, so the path's file name cannot be checked")
          } else {
            Paths.get(pathname).endsWith(Paths.get("samples", "FailSuite.scala")) shouldBe true
          }
        }
      }
    )

    eventScenarios(
      "A property check failure",
      "an AnyFunSuite whose test t runs a forAll from TableDrivenPropertyChecks whose property fails",
      "samples.PropertyCheckFailSuite",
      "the test's failure, holding a throwable marked a property check failure",
      failSuite(
        "samples.PropertyCheckFailSuite",
        "throwable: org.scalatest.exceptions.TableDrivenPropertyCheckFailedException property check",
        """"throwable":{"id":1,"className":"org.scalatest.exceptions.TableDrivenPropertyCheckFailedException","propertyCheck":true},""",
        """"message":"the property failed","throwable":{"id":1,"className":"org.scalatest.exceptions.TableDrivenPropertyCheckFailedException","message":"the property failed","propertyCheck":true},""",
        PropertyCheckFailure.reportedMessage
      )
    )

    eventScenarios(
      "A throwable whose message is null",
      "an AnyFunSuite whose test t throws new RuntimeException()",
      "samples.NullMessageFailSuite",
      "the test's failure, holding a RuntimeException with no message",
      failSuite(
        "samples.NullMessageFailSuite",
        "throwable: java.lang.RuntimeException null",
        """"throwable":{"id":1,"className":"java.lang.RuntimeException"},""",
        """"message":"","throwable":{"id":1,"className":"java.lang.RuntimeException"},""",
        NullMessageFailure.reportedMessage
      ),
      { run =>
        And("the throwable has no message")
        throwableOfTestFailed(run.eventLog).message shouldBe None
      }
    )

    eventScenarios(
      "An assertion with analysis",
      "an AnyFunSuite whose test t asserts that two instances of a case class differing in one field are equal",
      "samples.AnalysisFailSuite",
      "the test's failure, holding its analysis",
      failSuite(
        "samples.AnalysisFailSuite",
        s"analysis: [\"${AnalysisFailure.analysis}\"]",
        s""""analysis":["${AnalysisFailure.analysis}"],""",
        s""""message":"${AnalysisFailure.reportedMessage}","analysis":["${AnalysisFailure.analysis}"],""",
        AnalysisFailure.reportedMessage
      ),
      { run =>
        And("the analysis is the analysis that the TestFailed the emitter receives for the test carries, which the recording reporter receives too")
        val logged = decodedEventsIn(run.eventLog).collectFirst { case failed: DecodedEvent.TestFailed => failed.analysis }
        logged shouldBe Some(testFailedOf(run.reported).analysis)
      }
    )
  }
}

private object PropertyCheckFailure {
  val reportedMessage: String = List(
    "TestFailedException was thrown during property evaluation. (FailSuite.scala:26)",
    "  Message: 2 was not less than 2",
    "  Location: (FailSuite.scala:25)",
    "  Occurred at table row 1 (zero based, not counting headings), which had values (",
    "    n = 2",
    "  )"
  ).mkString("\n")
}

private object NullMessageFailure {
  val reportedMessage: String = "java.lang.RuntimeException was thrown."
}

private object AnalysisFailure {
  val analysis: String = "Point(y: 2 -> 3)"
  val reportedMessage: String = "Point(1, 2) did not equal Point(1, 3)"
}

private[tools] object TestEventLogEventsSpec {

  val runStarted: String = TestEventLogLine.runStarted(1L, "2026-01-02T03:04:05.678Z")
  val runStartingOfFramework: String = TestEventLogLine.runEvent("RunStarting", """"testCount":0,"configMap":{},""")
  val runCompleted: String = TestEventLogLine.runEvent("RunCompleted")
  val runEndedCompleted: String = TestEventLogLine.runEnded("completed")

  def frameworkRunOfOneTest(suiteName: String, suiteClassName: String, testName: String, eventsOfTest: (NodePath => String)*): List[String] = {
    val suite = NodePath.topLevel(suiteClassName)
    val test = suite.test(testName)
    List(
      runStarted,
      runStartingOfFramework,
      TestEventLogLine.structure(TestEventLogLine.topLevelSuite(suiteName, suiteClassName), TestEventLogLine.test(test, suite, 0, testName, s"- $testName")),
      TestEventLogLine.nodeEvent(suite, List(1), "SuiteStarting")
    ) ++ eventsOfTest.map(_(test)) ++ List(TestEventLogLine.nodeEvent(suite, List(1), "SuiteCompleted"), runCompleted, runEndedCompleted)
  }

  def ofTest(eventName: String, fields: String = ""): NodePath => String =
    test => TestEventLogLine.nodeEvent(test, List(1, 1), eventName, fields)

  def notationOfOneTest(suiteName: String, suiteClassName: String, testName: String, eventsOfTest: String*): String = {
    s"""
    run started
    run: RunStarting
    structure
      suite "$suiteName", class "$suiteClassName"
        test "$testName", label "- $testName"
    suite "$suiteName": SuiteStarting
    ${eventsOfTest.map(event => s"test \"$testName\": $event").mkString("\n    ")}
    suite "$suiteName": SuiteCompleted
    run: RunCompleted
    run ended completed
    """
  }

  def throwSuite(throwableInNotation: String, throwableInFormat: String): StatedRun = {
    StatedRun(
      Vector("RunStarting", "SuiteStarting ThrowSuite", "TestStarting throws", "TestFailed throws: outer", "SuiteCompleted ThrowSuite", "RunCompleted"),
      notationOfOneTest("ThrowSuite", "samples.ThrowSuite", "throws", "TestStarting", s"TestFailed(message: \"outer\", throwable: $throwableInNotation)"),
      frameworkRunOfOneTest(
        "ThrowSuite",
        "samples.ThrowSuite",
        "throws",
        ofTest("TestStarting"),
        ofTest("TestFailed", s""""message":"outer","throwable":$throwableInFormat,""")
      )
    )
  }
}

final class TestEventLogEventsDecoderSpec extends AnyWordSpec with Matchers with TestEventLogRuns {

  import TestEventLogEventsSpec.*

  "The decoder, given the lines of an event log whose test failed with a cause chain with a suppressed throwable, each throwable holding a frame" should {
    "yield each throwable of the chain with its frame" in {
      val frame = """{"className":"samples.ThrowSuite","methodName":"f","fileName":"ThrowSuite.scala","lineNumber":9,"text":"samples.ThrowSuite.f(ThrowSuite.scala:9)"}"""
      val stated = throwSuite(
        """java.lang.IllegalStateException "outer" frames ["samples.ThrowSuite.f(ThrowSuite.scala:9)"] """ +
          """suppressing [java.lang.RuntimeException "closing" frames ["samples.ThrowSuite.f(ThrowSuite.scala:9)"]] """ +
          """caused by java.io.IOException "inner" frames ["samples.ThrowSuite.f(ThrowSuite.scala:9)"]""",
        s"""{"id":1,"className":"java.lang.IllegalStateException","message":"outer","stackTrace":[$frame],"""
          + s""""cause":{"id":2,"className":"java.io.IOException","message":"inner","stackTrace":[$frame]},"""
          + s""""suppressed":[{"id":3,"className":"java.lang.RuntimeException","message":"closing","stackTrace":[$frame]}]}"""
      )
      EventLogComparison.mismatchWhenIs(decoded(stated.eventLogInFormat), built(stated.eventLogInNotation)) shouldBe None
    }
  }

  "The decoder, given the lines of an event log whose TestFailedException gives the failed code's pathname" should {
    "yield the throwable with that pathname in the file name's place" in {
      val events = decoded(
        frameworkRunOfOneTest(
          "FailSuite",
          "samples.FailSuite",
          "t",
          ofTest("TestStarting"),
          ofTest(
            "TestFailed",
            """"message":"boom","throwable":{"id":1,"className":"org.scalatest.exceptions.TestFailedException","message":"boom","stackDepth":{"fileName":"FailSuite.scala","filePathname":"/work/samples/FailSuite.scala","lineNumber":7}},"""
          )
        )
      )
      EventLogComparison.mismatchWhenIs(
        events,
        built(
          notationOfOneTest(
            "FailSuite",
            "samples.FailSuite",
            "t",
            "TestStarting",
            "TestFailed(throwable: org.scalatest.exceptions.TestFailedException \"boom\" at /work/samples/FailSuite.scala:7)"
          )
        )
      ) shouldBe None
    }
  }
}
