package org.scalatest.tools

import org.scalactic.Prettifier
import org.scalactic.source.Position
import org.scalatest.ConfigMap
import org.scalatest.events.*
import org.scalatest.exceptions.GeneratorDrivenPropertyCheckFailedException
import org.scalatest.exceptions.StackDepthException
import org.scalatest.exceptions.TestFailedException
import org.scalatest.matchers.should.Matchers
import org.scalatest.tools.StackFrames.withFrames
import org.scalatest.wordspec.AnyWordSpec
import scala.io.Codec
import scala.io.Source

private final case class PresentationFlags(
  name: String,
  unformatted: Boolean = false,
  allDurations: Boolean = false,
  shortStackTraces: Boolean = false,
  fullStackTraces: Boolean = false,
  reminder: Boolean = false,
  reminderWithShortStackTraces: Boolean = false,
  reminderWithFullStackTraces: Boolean = false,
  reminderWithoutCanceledTests: Boolean = false,
  filePathname: Boolean = false
)

private final case class EventCase(name: String, event: Event)

private final case class RunEndCase(name: String, event: Event, reminderEvents: Vector[ExceptionalEvent])

private final case class SummaryCase(
  name: String,
  runCompleted: Boolean,
  duration: Option[Long],
  summary: Option[Summary],
  exceptionalEvents: Vector[ExceptionalEvent]
)

// StringReporterRecordedText.txt holds the text and colour StringReporter must keep returning.
final class StringReporterRecordedTextSpec extends AnyWordSpec with Matchers {

  private val suiteName = "StackSpec"
  private val suiteId = "example.StackSpec"
  private val suiteClassName = Some("example.StackSpec")
  private val testName = "A stack should pop"
  private val testText = "should pop"
  private val threadName = "main"
  private val timeStamp = 1700000000000L
  private val duration = Some(1234L)
  private val firstOrdinal = new Ordinal(99)
  private val secondOrdinal = firstOrdinal.next
  private val thirdOrdinal = secondOrdinal.next
  private val fourthOrdinal = thirdOrdinal.next

  private val testNameInfo = NameInfo(suiteName, suiteId, suiteClassName, Some(testName))
  private val suiteNameInfo = NameInfo(suiteName, suiteId, suiteClassName, None)

  private def plainFailureWithCause: Throwable =
    withFrames(
      new IllegalStateException("boom", withFrames(new IllegalArgumentException("root cause\nsecond line"), "Disk", 3)),
      "Store",
      12
    )

  private def plainFailureWithoutMessage: Throwable = withFrames(new RuntimeException, "Store", 2)

  // Each position names its exception's second or third frame, so the failed code's stack depth is 1 or 2.
  private def stackDepthFailureWithCauses: Throwable = {
    val innermost = withFrames(new IllegalArgumentException, "Disk", 2)
    val inner = new TestFailedException(
      (_: StackDepthException) => Some("  the helper failed  "),
      Some(innermost),
      Left(Position("Helper.scala", "/src/Helper.scala", 11)),
      None,
      Vector.empty
    )
    val outer = new TestFailedException(
      (_: StackDepthException) => Some("1 did not equal 2"),
      Some(withFrames(inner, "Helper", 4)),
      Left(Position("Stack.scala", "/src/Stack.scala", 12)),
      None,
      Vector.empty
    )
    withFrames(outer, "Stack", 11)
  }

  private def stackDepthFailureWithoutPosition: Throwable =
    withFrames(
      new TestFailedException(
        (_: StackDepthException) => Some("the stack was empty"),
        None,
        Right((_: StackDepthException) => 1),
        None,
        Vector.empty
      ),
      "Stack",
      5
    )

  // The stack depth is past the last frame, so no frame gives a file name and line number.
  private def stackDepthFailureWithoutFileNameAndLineNumber: Throwable =
    withFrames(
      new TestFailedException(
        (_: StackDepthException) => Some("the stack was empty"),
        None,
        Right((_: StackDepthException) => 7),
        None,
        Vector.empty
      ),
      "Stack",
      2
    )

  private def stackDepthFailureWithoutMessage: Throwable =
    withFrames(
      new TestFailedException(
        (_: StackDepthException) => None,
        None,
        Left(Position("Stack.scala", "/src/Stack.scala", 10)),
        None,
        Vector.empty
      ),
      "Stack",
      3
    )

  private def propertyCheckFailure: Throwable =
    withFrames(
      new GeneratorDrivenPropertyCheckFailedException(
        (_: StackDepthException) => "Property check failed. (StackSpec.scala:30)\n  Falsified after 2 successful property evaluations.",
        Some(withFrames(new IllegalArgumentException("negative size"), "Disk", 3)),
        Left(Position("Stack.scala", "/src/Stack.scala", 13)),
        None,
        "undecorated",
        List(1),
        None,
        List.empty
      ),
      "Stack",
      4
    )

  // The StackDepth exception has a cause of its own, which the chain must not reach where it ends at the StackDepth exception.
  private def plainFailureCausedByStackDepthFailure: Throwable = {
    val innermost = withFrames(new IllegalArgumentException("root cause"), "Disk", 2)
    val inner = new TestFailedException(
      (_: StackDepthException) => Some("the helper failed"),
      Some(innermost),
      Left(Position("Helper.scala", "/src/Helper.scala", 11)),
      None,
      Vector.empty
    )
    withFrames(new IllegalStateException("boom", withFrames(inner, "Helper", 4)), "Store", 3)
  }

  private val throwablesOfMessages: Vector[(String, () => Throwable)] = Vector(
    "a plain exception with a cause" -> (() => plainFailureWithCause),
    "a plain exception without a message" -> (() => plainFailureWithoutMessage),
    "a StackDepth exception with causes" -> (() => stackDepthFailureWithCauses)
  )

  private val throwables: Vector[(String, () => Throwable)] = throwablesOfMessages ++ Vector(
    "a StackDepth exception without a position" -> (() => stackDepthFailureWithoutPosition),
    "a StackDepth exception without a message" -> (() => stackDepthFailureWithoutMessage),
    "a property check failure" -> (() => propertyCheckFailure),
    "a StackDepth exception without a file name and line number" -> (() => stackDepthFailureWithoutFileNameAndLineNumber),
    "a plain exception caused by a StackDepth exception" -> (() => plainFailureCausedByStackDepthFailure)
  )

  private val indentedTwoLevels: Option[Formatter] = Some(IndentedText("    - " + testText, testText, 2))

  private val formattersOfErrorsWithThrowable: Vector[(String, Option[Formatter])] = Vector(
    "at the top level" -> Some(IndentedText("- " + testText, testText, 0)),
    "indented two levels" -> indentedTwoLevels,
    "without a formatter" -> None
  )

  private val formatters: Vector[(String, Option[Formatter])] =
    formattersOfErrorsWithThrowable :+ ("with a motion to suppress" -> Some(MotionToSuppress))

  private val nameInfos: Vector[(String, Option[NameInfo])] = Vector(
    "in a test" -> Some(testNameInfo),
    "in a suite" -> Some(suiteNameInfo),
    "in no suite" -> None
  )

  private def recordedEvents: Vector[RecordableEvent] = Vector(
    InfoProvided(
      firstOrdinal,
      "recorded info",
      Some(testNameInfo),
      Some(plainFailureWithCause),
      Some(IndentedText("      + recorded info", "recorded info", 3)),
      threadName = threadName,
      timeStamp = timeStamp
    ),
    MarkupProvided(
      firstOrdinal,
      "recorded markup",
      Some(testNameInfo),
      Some(IndentedText("      recorded markup", "recorded markup", 3)),
      threadName = threadName,
      timeStamp = timeStamp
    ),
    InfoProvided(firstOrdinal, "recorded info without a formatter", Some(testNameInfo), threadName = threadName, timeStamp = timeStamp),
    MarkupProvided(
      firstOrdinal,
      "suppressed markup",
      Some(testNameInfo),
      Some(MotionToSuppress),
      threadName = threadName,
      timeStamp = timeStamp
    )
  )

  private def testFailed(
    message: String,
    throwable: Option[Throwable],
    formatter: Option[Formatter],
    analysis: Vector[String] = Vector.empty,
    recorded: Vector[RecordableEvent] = Vector.empty,
    ordinal: Ordinal = firstOrdinal,
    name: String = testName
  ): TestFailed =
    TestFailed(
      ordinal,
      message,
      suiteName,
      suiteId,
      suiteClassName,
      name,
      testText,
      recorded,
      analysis,
      throwable,
      duration,
      formatter,
      threadName = threadName,
      timeStamp = timeStamp
    )

  private def testCanceled(
    message: String,
    throwable: Option[Throwable],
    formatter: Option[Formatter],
    recorded: Vector[RecordableEvent] = Vector.empty,
    ordinal: Ordinal = firstOrdinal,
    name: String = testName
  ): TestCanceled =
    TestCanceled(
      ordinal,
      message,
      suiteName,
      suiteId,
      suiteClassName,
      name,
      testText,
      recorded,
      throwable,
      duration,
      formatter,
      threadName = threadName,
      timeStamp = timeStamp
    )

  private def suiteAborted(
    message: String,
    throwable: Option[Throwable],
    formatter: Option[Formatter],
    ordinal: Ordinal = firstOrdinal
  ): SuiteAborted =
    SuiteAborted(
      ordinal,
      message,
      suiteName,
      suiteId,
      suiteClassName,
      throwable,
      duration,
      formatter,
      threadName = threadName,
      timeStamp = timeStamp
    )

  private def runAborted(message: String, throwable: Option[Throwable], formatter: Option[Formatter]): RunAborted =
    RunAborted(firstOrdinal, message, throwable, duration, None, formatter, threadName = threadName, timeStamp = timeStamp)

  private val errorEvents: Vector[(String, (String, Option[Throwable], Option[Formatter]) => Event)] = Vector(
    "TestFailed" -> ((message, throwable, formatter) => testFailed(message, throwable, formatter)),
    "TestCanceled" -> ((message, throwable, formatter) => testCanceled(message, throwable, formatter)),
    "SuiteAborted" -> ((message, throwable, formatter) => suiteAborted(message, throwable, formatter)),
    "RunAborted" -> ((message, throwable, formatter) => runAborted(message, throwable, formatter))
  )

  private val messageEvents: Vector[(String, (Option[NameInfo], Option[Throwable], Option[Formatter]) => Event)] = Vector(
    "InfoProvided" -> ((nameInfo, throwable, formatter) =>
      InfoProvided(firstOrdinal, "the message", nameInfo, throwable, formatter, threadName = threadName, timeStamp = timeStamp)
    ),
    "AlertProvided" -> ((nameInfo, throwable, formatter) =>
      AlertProvided(firstOrdinal, "the message", nameInfo, throwable, formatter, threadName = threadName, timeStamp = timeStamp)
    ),
    "NoteProvided" -> ((nameInfo, throwable, formatter) =>
      NoteProvided(firstOrdinal, "the message", nameInfo, throwable, formatter, threadName = threadName, timeStamp = timeStamp)
    )
  )

  private val formattedEvents: Vector[(String, Option[Formatter] => Event)] = Vector(
    "SuiteStarting" -> (formatter =>
      SuiteStarting(firstOrdinal, suiteName, suiteId, suiteClassName, formatter, threadName = threadName, timeStamp = timeStamp)
    ),
    "SuiteCompleted" -> (formatter =>
      SuiteCompleted(firstOrdinal, suiteName, suiteId, suiteClassName, duration, formatter, threadName = threadName, timeStamp = timeStamp)
    ),
    "TestStarting" -> (formatter =>
      TestStarting(
        firstOrdinal,
        suiteName,
        suiteId,
        suiteClassName,
        testName,
        testText,
        formatter,
        threadName = threadName,
        timeStamp = timeStamp
      )
    ),
    "TestSucceeded" -> (formatter =>
      TestSucceeded(
        firstOrdinal,
        suiteName,
        suiteId,
        suiteClassName,
        testName,
        testText,
        recordedEvents,
        duration,
        formatter,
        threadName = threadName,
        timeStamp = timeStamp
      )
    ),
    "TestIgnored" -> (formatter =>
      TestIgnored(
        firstOrdinal,
        suiteName,
        suiteId,
        suiteClassName,
        testName,
        testText,
        formatter,
        threadName = threadName,
        timeStamp = timeStamp
      )
    ),
    "TestPending" -> (formatter =>
      TestPending(
        firstOrdinal,
        suiteName,
        suiteId,
        suiteClassName,
        testName,
        testText,
        recordedEvents,
        duration,
        formatter,
        threadName = threadName,
        timeStamp = timeStamp
      )
    ),
    "ScopeOpened in a suite" -> (formatter =>
      ScopeOpened(firstOrdinal, "A stack", suiteNameInfo, formatter, threadName = threadName, timeStamp = timeStamp)
    ),
    "ScopeOpened in a test" -> (formatter =>
      ScopeOpened(firstOrdinal, "A stack", testNameInfo, formatter, threadName = threadName, timeStamp = timeStamp)
    ),
    "ScopeClosed" -> (formatter =>
      ScopeClosed(firstOrdinal, "A stack", suiteNameInfo, formatter, threadName = threadName, timeStamp = timeStamp)
    ),
    "ScopePending" -> (formatter =>
      ScopePending(firstOrdinal, "A stack", suiteNameInfo, formatter, threadName = threadName, timeStamp = timeStamp)
    ),
    "MarkupProvided in a test" -> (formatter =>
      MarkupProvided(firstOrdinal, "*markup*", Some(testNameInfo), formatter, threadName = threadName, timeStamp = timeStamp)
    ),
    "MarkupProvided in a suite" -> (formatter =>
      MarkupProvided(firstOrdinal, "*markup*", Some(suiteNameInfo), formatter, threadName = threadName, timeStamp = timeStamp)
    ),
    "MarkupProvided in no suite" -> (formatter =>
      MarkupProvided(firstOrdinal, "*markup*", None, formatter, threadName = threadName, timeStamp = timeStamp)
    )
  )

  private val eventCases: Vector[EventCase] = {
    val errorCasesWithoutThrowable =
      for {
        (eventName, event) <- errorEvents
        (formatterName, formatter) <- formatters
      } yield EventCase(s"$eventName with no throwable, $formatterName", event("the message", None, formatter))
    val errorCasesWithThrowable =
      for {
        (eventName, event) <- errorEvents
        (throwableName, throwable) <- throwables
        (formatterName, formatter) <- formattersOfErrorsWithThrowable
      } yield EventCase(s"$eventName by $throwableName, $formatterName", event("the message", Some(throwable()), formatter))
    val messageCasesWithoutThrowable =
      for {
        (eventName, event) <- messageEvents
        (nameInfoName, nameInfo) <- nameInfos
        (formatterName, formatter) <- formatters
      } yield EventCase(s"$eventName $nameInfoName with no throwable, $formatterName", event(nameInfo, None, formatter))
    val messageCasesWithThrowable =
      for {
        (eventName, event) <- messageEvents
        (throwableName, throwable) <- throwablesOfMessages
      } yield EventCase(s"$eventName in a test with $throwableName", event(Some(testNameInfo), Some(throwable()), indentedTwoLevels))
    val formattedCases =
      for {
        (eventName, event) <- formattedEvents
        (formatterName, formatter) <- formatters
      } yield EventCase(s"$eventName, $formatterName", event(formatter))
    errorCasesWithoutThrowable ++ errorCasesWithThrowable ++ messageCasesWithoutThrowable ++ messageCasesWithThrowable ++
      formattedCases ++ Vector(
      EventCase(
        "TestFailed with a blank message by a StackDepth exception",
        testFailed("  ", Some(stackDepthFailureWithCauses), indentedTwoLevels)
      ),
      EventCase(
        "TestFailed with a blank message by a plain exception",
        testFailed("", Some(plainFailureWithCause), indentedTwoLevels)
      ),
      EventCase(
        "TestFailed with a blank message by a property check failure",
        testFailed("", Some(propertyCheckFailure), indentedTwoLevels)
      ),
      EventCase(
        "TestFailed with a message of several lines",
        testFailed("  first line\nsecond line\n\nfourth line  ", Some(stackDepthFailureWithCauses), indentedTwoLevels)
      ),
      EventCase(
        "TestFailed with analysis lines by a StackDepth exception",
        testFailed(
          "the message",
          Some(stackDepthFailureWithCauses),
          indentedTwoLevels,
          Vector("first analysis line", "second analysis line")
        )
      ),
      EventCase(
        "TestFailed with analysis lines and no throwable",
        testFailed("the message", None, indentedTwoLevels, Vector("first analysis line", "second analysis line"))
      ),
      EventCase(
        "TestFailed with recorded events",
        testFailed("the message", Some(stackDepthFailureWithCauses), indentedTwoLevels, recorded = recordedEvents)
      ),
      EventCase(
        "TestCanceled with recorded events",
        testCanceled("the message", Some(stackDepthFailureWithCauses), indentedTwoLevels, recordedEvents)
      ),
      EventCase("DiscoveryStarting", DiscoveryStarting(firstOrdinal, ConfigMap.empty, threadName, timeStamp)),
      EventCase("DiscoveryCompleted with a duration", DiscoveryCompleted(firstOrdinal, duration, threadName, timeStamp)),
      EventCase("DiscoveryCompleted without a duration", DiscoveryCompleted(firstOrdinal, None, threadName, timeStamp)),
      EventCase("RunStarting", RunStarting(firstOrdinal, 7, ConfigMap.empty, threadName = threadName, timeStamp = timeStamp))
    )
  }

  private val eventFlags: Vector[PresentationFlags] = {
    for {
      unformatted <- Vector(false, true)
      format = if (unformatted) "unformatted" else "formatted"
      flags <- Vector(
        PresentationFlags(format),
        PresentationFlags(s"$format with D", allDurations = true),
        PresentationFlags(s"$format with S", shortStackTraces = true),
        PresentationFlags(s"$format with F", fullStackTraces = true),
        PresentationFlags(s"$format with V", filePathname = true),
        PresentationFlags(
          s"$format with D, S, F and V",
          allDurations = true,
          shortStackTraces = true,
          fullStackTraces = true,
          filePathname = true
        )
      )
    } yield flags.copy(unformatted = unformatted)
  }

  private val summaryFlags: Vector[PresentationFlags] = Vector(
    PresentationFlags("with no reminder"),
    PresentationFlags("with I", reminder = true),
    PresentationFlags("with T and not I", reminderWithShortStackTraces = true),
    PresentationFlags("with G and not I", reminderWithFullStackTraces = true),
    PresentationFlags("with K and not I", reminderWithoutCanceledTests = true),
    PresentationFlags("with I and T", reminder = true, reminderWithShortStackTraces = true),
    PresentationFlags("with I and G", reminder = true, reminderWithFullStackTraces = true),
    PresentationFlags("with I and K", reminder = true, reminderWithoutCanceledTests = true),
    PresentationFlags(
      "with I, T, G and K",
      reminder = true,
      reminderWithShortStackTraces = true,
      reminderWithFullStackTraces = true,
      reminderWithoutCanceledTests = true
    ),
    PresentationFlags("with I, D and V", reminder = true, allDurations = true, filePathname = true),
    PresentationFlags(
      "with I, T, D and V",
      reminder = true,
      reminderWithShortStackTraces = true,
      allDurations = true,
      filePathname = true
    ),
    PresentationFlags("with D", allDurations = true)
  )

  // Given out of ordinal order, since the reminders are sorted by ordinal.
  private def exceptionalEvents: Vector[ExceptionalEvent] = Vector(
    suiteAborted("the suite aborted", Some(plainFailureWithCause), None, fourthOrdinal),
    testFailed(
      "the message",
      Some(stackDepthFailureWithCauses),
      indentedTwoLevels,
      Vector("first analysis line", "second analysis line"),
      ordinal = secondOrdinal
    ),
    testCanceled("the test was canceled", Some(propertyCheckFailure), None, ordinal = thirdOrdinal, name = "a name that does not end in the text"),
    testFailed("", Some(stackDepthFailureWithoutPosition), None, ordinal = firstOrdinal)
  )

  private val summaryOfSeveralFailuresAndAborts: Option[Summary] = Some(Summary(3, 2, 1, 1, 2, 4, 3, 5))

  private val summaries: Vector[(String, Option[Summary])] = Vector(
    "no summary" -> None,
    "every test passing" -> Some(Summary(3, 0, 1, 2, 0, 2, 0, 0)),
    "no test run" -> Some(Summary(0, 0, 0, 0, 0, 1, 0, 0)),
    "one test failed and one suite aborted" -> Some(Summary(3, 1, 0, 0, 1, 2, 1, 0)),
    "several tests failed, several suites aborted and scopes pending" -> summaryOfSeveralFailuresAndAborts,
    "one suite aborted and no test failed" -> Some(Summary(3, 0, 0, 0, 0, 2, 1, 0))
  )

  private val runEnds: Vector[(String, Boolean)] = Vector("a completed run" -> true, "a stopped run" -> false)

  private val runDurations: Vector[(String, Option[Long])] = Vector("with a duration" -> duration, "without a duration" -> None)

  private val summaryCases: Vector[SummaryCase] = {
    val withoutReminders =
      for {
        (runEndName, runCompleted) <- runEnds
        (durationName, runDuration) <- runDurations
        (summaryName, summary) <- summaries
      } yield SummaryCase(s"$runEndName $durationName and $summaryName", runCompleted, runDuration, summary, Vector.empty)
    val withReminders =
      for ((runEndName, runCompleted) <- runEnds)
        yield SummaryCase(
          s"$runEndName with failed, canceled and aborted events",
          runCompleted,
          duration,
          summaryOfSeveralFailuresAndAborts,
          exceptionalEvents
        )
    withoutReminders ++ withReminders
  }

  private val runEndCases: Vector[RunEndCase] = {
    for {
      (reminderName, reminderEvents) <- Vector("with no event to remind of" -> Vector.empty, "with events to remind of" -> exceptionalEvents)
      (eventName, event) <- Vector(
        "RunCompleted" -> RunCompleted(firstOrdinal, duration, summaryOfSeveralFailuresAndAborts, threadName = threadName, timeStamp = timeStamp),
        "RunStopped" -> RunStopped(firstOrdinal, duration, summaryOfSeveralFailuresAndAborts, threadName = threadName, timeStamp = timeStamp)
      )
    } yield RunEndCase(s"$eventName $reminderName", event, reminderEvents)
  }

  private def fragmentsFor(event: Event, flags: PresentationFlags, reminderEvents: Vector[ExceptionalEvent]): Vector[Fragment] =
    StringReporter.fragmentsForEvent(
      event,
      flags.unformatted,
      flags.allDurations,
      flags.shortStackTraces,
      flags.fullStackTraces,
      flags.reminder,
      flags.reminderWithShortStackTraces,
      flags.reminderWithFullStackTraces,
      flags.reminderWithoutCanceledTests,
      flags.filePathname,
      reminderEvents
    )

  private def summaryFragmentsFor(summaryCase: SummaryCase, flags: PresentationFlags): Vector[Fragment] =
    StringReporter.summaryFragments(
      summaryCase.runCompleted,
      summaryCase.duration,
      summaryCase.summary,
      summaryCase.exceptionalEvents,
      flags.allDurations,
      flags.reminder,
      flags.reminderWithShortStackTraces,
      flags.reminderWithFullStackTraces,
      flags.reminderWithoutCanceledTests,
      flags.filePathname
    )

  // The run-aborted text holds the platform's line separator, which the recorded text holds as a line feed.
  private def recordedForm(fragment: Fragment): String = {
    val text = fragment.text
      .replace(Prettifier.lineSeparator, "\n")
      .replace("\\", "\\\\")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
    s"${fragment.ansiColor}|$text"
  }

  private def rendered(allFlags: Vector[PresentationFlags], fragments: PresentationFlags => Vector[Fragment]): Vector[(String, Vector[String])] =
    allFlags.map(flags => flags.name -> fragments(flags).map(recordedForm))

  private val caseHeading = "# "
  private val flagsHeading = "## "

  private val recorded: Map[String, Vector[(String, Vector[String])]] = {
    val source = Source.fromInputStream(getClass.getResourceAsStream("StringReporterRecordedText.txt"))(Codec.UTF8)
    val lines =
      try {
        source.getLines().toVector
      } finally {
        source.close()
      }
    val cases = Map.newBuilder[String, Vector[(String, Vector[String])]]
    var caseName = ""
    var caseFlags = Vector.empty[(String, Vector[String])]
    def closeCase(): Unit = {
      if (caseName.nonEmpty) {
        cases += caseName -> caseFlags
      }
    }
    lines.foreach { line =>
      if (line.startsWith(flagsHeading)) {
        caseFlags = caseFlags :+ (line.drop(flagsHeading.length) -> Vector.empty)
      } else if (line.startsWith(caseHeading)) {
        closeCase()
        caseName = line.drop(caseHeading.length)
        caseFlags = Vector.empty
      } else {
        val (flagsName, fragments) = caseFlags.last
        caseFlags = caseFlags.init :+ (flagsName -> (fragments :+ line))
      }
    }
    closeCase()
    cases.result()
  }

  "StringReporter's fragmentsForEvent" should {
    eventCases.foreach { eventCase =>
      s"return the recorded fragments for ${eventCase.name}" in {
        rendered(eventFlags, fragmentsFor(eventCase.event, _, Vector.empty)) shouldBe recorded(eventCase.name)
      }
    }
    runEndCases.foreach { runEndCase =>
      s"return the recorded fragments for ${runEndCase.name}" in {
        rendered(summaryFlags, fragmentsFor(runEndCase.event, _, runEndCase.reminderEvents)) shouldBe recorded(runEndCase.name)
      }
    }
    "throw a NullPointerException for a blank message and a throwable without a message" in {
      val event = testFailed("", Some(plainFailureWithoutMessage), None)
      a[NullPointerException] should be thrownBy fragmentsFor(event, eventFlags.head, Vector.empty)
    }
  }

  "StringReporter's summaryFragments" should {
    summaryCases.foreach { summaryCase =>
      s"return the recorded fragments for ${summaryCase.name}" in {
        rendered(summaryFlags, summaryFragmentsFor(summaryCase, _)) shouldBe recorded(summaryCase.name)
      }
    }
  }

  "The recorded text" should {
    "hold only the cases this spec compares" in {
      val compared = eventCases.map(_.name) ++ runEndCases.map(_.name) ++ summaryCases.map(_.name)
      recorded.keySet.diff(compared.toSet) shouldBe Set.empty
    }
  }
}
