package org.scalatest.tools

import org.scalatest.GivenWhenThen
import org.scalatest.events.AlertProvided
import org.scalatest.events.DecodedEvent
import org.scalatest.events.DecodedThrowable
import org.scalatest.events.Event
import org.scalatest.events.EventLogComparison
import org.scalatest.events.InfoProvided
import org.scalatest.events.MarkupProvided
import org.scalatest.events.NoteProvided
import org.scalatest.events.RecordableEvent
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
import samples.MessageCallTimes
import samples.ReportsText
import samples.ThrowSuite

final class TestEventLogEventsSpec extends AnyFeatureSpec with GivenWhenThen with Matchers with TestEventLogRuns {

  /**
   * A run as a criterion states it.
   *
   * @param reported what the run's reporters receive, in order, whether or not the run writes an event log
   * @param eventLogInNotation the run's whole event log, in the event notation
   * @param eventLogInFormat the lines the run writes, holding only the keys the criterion fixes
   * @param linesTheDecoderReads that event log as the format writes it, one line for each event, where its lines
   *                             need keys the criterion does not fix
   */
  private final case class StatedRun(
    reported: Vector[String],
    eventLogInNotation: String,
    eventLogInFormat: List[String],
    linesTheDecoderReads: Option[List[String]] = None
  )

  private def recordedIn(recorded: Seq[RecordableEvent]): String = {
    val messages = recorded.map {
      case info: InfoProvided => s"InfoProvided ${info.message}"
      case markup: MarkupProvided => s"MarkupProvided ${markup.text}"
    }
    if (messages.isEmpty) "" else messages.mkString(", recording ", ", ", "")
  }

  /** What a run's reporters were told, in the order they were told it. */
  private def reported(events: Vector[Event]): Vector[String] = {
    events.collect {
      case _: RunStarting => "RunStarting"
      case _: RunCompleted => "RunCompleted"
      case starting: SuiteStarting => s"SuiteStarting ${starting.suiteName}"
      case completed: SuiteCompleted => s"SuiteCompleted ${completed.suiteName}"
      case starting: TestStarting => s"TestStarting ${starting.testName}"
      case succeeded: TestSucceeded => s"TestSucceeded ${succeeded.testName}${recordedIn(succeeded.recordedEvents)}"
      case failed: TestFailed => s"TestFailed ${failed.testName}: ${failed.message}${recordedIn(failed.recordedEvents)}"
      case info: InfoProvided => s"InfoProvided ${info.message}"
      case note: NoteProvided => s"NoteProvided ${note.message}"
      case alert: AlertProvided => s"AlertProvided ${alert.message}"
      case markup: MarkupProvided => s"MarkupProvided ${markup.text}"
      case other => other.getClass.getSimpleName
    }
  }

  /** Each event a run's reporters were told, with its ordinal and the ordinals of the events it records. */
  private def withOrdinals(events: Vector[Event]): Vector[(List[Int], String, Seq[List[Int]])] = {
    val told = reported(events)
    events.zip(told).map { (event, telling) =>
      val recorded = event match {
        case succeeded: TestSucceeded => succeeded.recordedEvents.map(_.ordinal.toList)
        case failed: TestFailed => failed.recordedEvents.map(_.ordinal.toList)
        case _ => Vector.empty
      }
      (event.ordinal.toList, telling, recorded)
    }
  }

  private val runStarted = TestEventLogLine.runStarted(1L, "2026-01-02T03:04:05.678Z")
  private val runStartingOfFramework = TestEventLogLine.runEvent("RunStarting", """"testCount":0,"configMap":{},""")
  private val runCompleted = TestEventLogLine.runEvent("RunCompleted")
  private val runEndedCompleted = TestEventLogLine.runEnded("completed")

  /** A Framework run of one suite, node 1, whose one test is node 2. */
  private def frameworkRunOfOneTest(suiteName: String, suiteClassName: String, testName: String, eventsOfTest: String*): List[String] = {
    List(
      runStarted,
      runStartingOfFramework,
      TestEventLogLine.structure(TestEventLogLine.suite(1, suiteName, suiteClassName), TestEventLogLine.test(2, 1, 0, testName, s"- $testName")),
      TestEventLogLine.suiteEvent(1, 1, "SuiteStarting")
    ) ++ eventsOfTest ++ List(TestEventLogLine.suiteEvent(1, 1, "SuiteCompleted"), runCompleted, runEndedCompleted)
  }

  private def ofTest(eventName: String, fields: String = ""): String = TestEventLogLine.scopeOrTestEvent(2, 1, 1, eventName, fields)

  private def notationOfOneTest(suiteName: String, suiteClassName: String, testName: String, eventsOfTest: String*): String = {
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

  /** The text of a string as a JSON string and the event notation both write it: Java's escapes, and `\\uXXXX` beyond ASCII. */
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

  private def decodedEventsIn(log: Vector[TestEventLogEvent]): Vector[DecodedEvent] =
    log.collect { case TestEventLogEvent.ScalaTestEvent(_, event) => event }

  private def throwableOfTestFailed(log: Vector[TestEventLogEvent]): DecodedThrowable =
    decodedEventsIn(log).collectFirst { case failed: DecodedEvent.TestFailed => failed.throwable }.flatten.getOrElse(fail("no TestFailed holds a throwable"))

  /**
   * Registers the scenario of the run that writes its events to the event log, and the scenario of the decoder that
   * reads them. `checkRun` makes the scenario's further checks of the run, given the events its reporters received
   * and its event log.
   */
  private def eventScenarios(
    scenario: String,
    suite: String,
    suiteClassName: String,
    eventLogHolds: String,
    stated: StatedRun,
    checkRun: (Vector[Event], Vector[TestEventLogEvent]) => Unit = (_, _) => ()
  ): Unit = {
    Scenario(scenario) {
      thisProcess
      Given(suite)
      When("Framework runs it without the runner argument")
      val reportedWithoutTheArgument = eventsReportedWhenLaunched(Launch.ByFramework, suiteClassName, Nil)

      Then("the run's reporters are told what ScalaTest 3.2.20 tells them")
      reported(reportedWithoutTheArgument) shouldBe stated.reported

      TemporaryEventLog.in { eventLogFile =>
        MessageCallTimes.clear(suiteClassName)
        When("Framework runs it with the runner argument")
        val reportedWithTheArgument = eventsReportedWhenLaunched(Launch.ByFramework, suiteClassName, runnerArgument(eventLogFile))
        val lines = TemporaryEventLog.lines(eventLogFile)

        Then("the run's reporters are told the same, each event with the ordinal and the recorded events it has without the argument")
        withOrdinals(reportedWithTheArgument) shouldBe withOrdinals(reportedWithoutTheArgument)

        And(s"the event log is $eventLogHolds")
        val log = decodedOrFail(lines)
        StatedLines.mismatchWhenIs(lines, stated.eventLogInFormat) shouldBe None
        EventLogComparison.mismatchWhenIs(log, built(stated.eventLogInNotation)) shouldBe None
        checkRun(reportedWithTheArgument, log)
      }
    }

    Scenario(s"The decoder reads the event log of: $scenario") {
      When("the decoder is given the lines of that event log")
      val events = decodedOrFail(stated.linesTheDecoderReads.getOrElse(stated.eventLogInFormat))

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

  private def messageTimeStampsArePlacedInTheirCalls(suiteClassName: String)(reported: Vector[Event], log: Vector[TestEventLogEvent]): Unit = {
    And("each message's timestamp is the time the test called it")
    val timeStamps = decodedEventsIn(log).collect {
      case info: DecodedEvent.InfoProvided => info.timeStamp
      case note: DecodedEvent.NoteProvided => note.timeStamp
      case alert: DecodedEvent.AlertProvided => alert.timeStamp
      case markup: DecodedEvent.MarkupProvided => markup.timeStamp
    }
    val callTimes = MessageCallTimes.of(Class.forName(suiteClassName))
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
        messageTimeStampsArePlacedInTheirCalls(suiteClassName)
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
      { (_, log) =>
        And("each of the three holds every frame of the thrown object, each with the text StackTraceElement.toString gives it")
        val thrown = ThrowSuite.thrown.get
        val decoded = throwableOfTestFailed(log)
        def framesOf(throwable: Throwable): Vector[String] = throwable.getStackTrace.toVector.map(_.toString)
        decoded.frames shouldBe framesOf(thrown)
        decoded.suppressed.map(_.frames) shouldBe Vector(framesOf(thrown.getSuppressed.head))
        decoded.cause.map(_.frames) shouldBe Some(framesOf(thrown.getCause))
      }
    )

    Scenario("The decoder reads the frames of each throwable of a cause chain with a suppressed throwable") {
      val frame = """{"className":"samples.ThrowSuite","methodName":"f","fileName":"ThrowSuite.scala","lineNumber":9,"text":"samples.ThrowSuite.f(ThrowSuite.scala:9)"}"""
      val stated = throwSuite(
        """java.lang.IllegalStateException "outer" frames ["samples.ThrowSuite.f(ThrowSuite.scala:9)"] """ +
          """suppressing [java.lang.RuntimeException "closing" frames ["samples.ThrowSuite.f(ThrowSuite.scala:9)"]] """ +
          """caused by java.io.IOException "inner" frames ["samples.ThrowSuite.f(ThrowSuite.scala:9)"]""",
        s"""{"id":1,"className":"java.lang.IllegalStateException","message":"outer","stackTrace":[$frame],"""
          + s""""cause":{"id":2,"className":"java.io.IOException","message":"inner","stackTrace":[$frame]},"""
          + s""""suppressed":[{"id":3,"className":"java.lang.RuntimeException","message":"closing","stackTrace":[$frame]}]}"""
      )
      When("the decoder is given the lines of an event log whose test failed with such a chain, each throwable holding a frame")
      val events = decodedOrFail(stated.eventLogInFormat)

      Then("it yields each throwable of the chain with its frame")
      EventLogComparison.mismatchWhenIs(events, built(stated.eventLogInNotation)) shouldBe None
    }

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
        Some(run(testFailedTheDecoderReads))
      )
    }

    def testFailedOf(reported: Vector[Event]): TestFailed =
      reported.collectFirst { case failed: TestFailed => failed }.getOrElse(fail("the reporters were told of no TestFailed"))

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
      { (reported, log) =>
        And("the throwable holds the pathname of FailSuite.scala the run's reporters are told")
        val toldPathname = testFailedOf(reported).throwable.collect { case failed: StackDepth => failed.failedCodeFilePathname }.flatten
        toldPathname should not be empty
        throwableOfTestFailed(log).stackDepth.flatMap(_.filePathname) shouldBe toldPathname
      }
    )

    Scenario("The decoder reads a StackDepth exception's pathname") {
      When("the decoder is given the lines of an event log whose TestFailedException gives the failed code's pathname")
      val events = decodedOrFail(
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

      Then("it yields the throwable with that pathname in the file name's place")
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
      { (_, log) =>
        And("the throwable has no message")
        throwableOfTestFailed(log).message shouldBe None
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
      { (reported, log) =>
        And("the analysis is the one the run's reporters are told")
        val logged = decodedEventsIn(log).collectFirst { case failed: DecodedEvent.TestFailed => failed.analysis }
        logged shouldBe Some(testFailedOf(reported).analysis)
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
  val reportedMessage = "java.lang.RuntimeException was thrown."
}

private object AnalysisFailure {
  val analysis = "Point(y: 2 -> 3)"
  val reportedMessage = "Point(1, 2) did not equal Point(1, 3)"
}
