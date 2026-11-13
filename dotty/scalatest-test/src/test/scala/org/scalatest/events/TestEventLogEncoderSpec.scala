package org.scalatest.events

import org.scalactic.source.Position
import org.scalatest.ConfigMap
import org.scalatest.exceptions.GeneratorDrivenPropertyCheckFailedException
import org.scalatest.exceptions.StackDepthException
import org.scalatest.exceptions.TestFailedException
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

private final class EqualToEveryThrowable extends RuntimeException("same") {
  override def equals(other: Any): Boolean = true
  override def hashCode: Int = 1
}

final class TestEventLogEncoderSpec extends AnyWordSpec with Matchers {

  private val ordinal = new Ordinal(99)
  private val timeStamp = 1700000000000L
  private val nameInfo = NameInfo("StackSpec", "example.StackSpec", Some("example.StackSpec"), Some("A stack should pop"))
  private val summary = Summary(1, 2, 3, 4, 5, 6, 7, 8)
  private val suiteExecution = Some(NodeExecution(NodeId(1), 1, None))
  private val testExecution = Some(NodeExecution(NodeId(3), 2, Some(1)))
  private val lineInFile = Some(LineInFile(12, "StackSpec.scala", None))

  private val boomText = """"throwable":{"id":1,"className":"java.lang.IllegalStateException","message":"boom"},"""
  private val summaryText =
    """"summary":{"testsSucceededCount":1,"testsFailedCount":2,"testsIgnoredCount":3,"testsPendingCount":4,""" +
      """"testsCanceledCount":5,"suitesCompletedCount":6,"suitesAbortedCount":7,"scopesPendingCount":8},"""
  private val lineInFileText = """"location":{"kind":"LineInFile","lineNumber":12,"fileName":"StackSpec.scala"},"""
  private val threadAndTimeText = """"threadName":"main","timeStamp":1700000000000}"""
  private val testEventText = """{"item":"event","node":3,"execution":2,"parentExecution":1,"event":"""
  private val suiteEventText = """{"item":"event","node":1,"execution":1,"event":"""
  private val runEventText = """{"item":"event","event":"""

  private def withoutStackTrace[T <: Throwable](throwable: T): T = {
    throwable.setStackTrace(Array.empty)
    throwable
  }

  private def boom: Option[Throwable] = Some(withoutStackTrace(new IllegalStateException("boom")))

  private def suiteAbortedBy(throwable: Throwable): String = {
    TestEventLogEncoder.event(
      SuiteAborted(
        ordinal = ordinal,
        message = "aborted",
        suiteName = "StackSpec",
        suiteId = "example.StackSpec",
        suiteClassName = Some("example.StackSpec"),
        throwable = Some(throwable),
        threadName = "main",
        timeStamp = timeStamp
      ),
      suiteExecution
    )
  }

  private def suiteAbortedLine(throwableText: String): String =
    """{"item":"event","node":1,"execution":1,"event":"SuiteAborted","message":"aborted","throwable":""" + throwableText +
      ""","threadName":"main","timeStamp":1700000000000}""" + "\n"

  private def infoLine(message: String): String = {
    TestEventLogEncoder.event(
      InfoProvided(ordinal = ordinal, message = message, nameInfo = None, threadName = "main", timeStamp = timeStamp),
      None
    )
  }

  "The test event log encoder" when {

    "writing the runStarted event" should {
      "write the item, the PID and the start time, in that order, on one line" in {
        TestEventLogEncoder.runStarted(4242L, "2026-11-13T02:54:37.123456Z") shouldBe
          """{"item":"runStarted","pid":4242,"startTime":"2026-11-13T02:54:37.123456Z"}""" + "\n"
      }
    }

    "writing a structure event" should {
      "write a top-level suite without parent or position, then its nodes with only the keys of their kind" in {
        val nodes = Vector(
          StructureNode.Suite(NodeId(1), None, None, "StackSpec", "example.StackSpec", "example.StackSpec"),
          StructureNode.Scope(NodeId(2), NodeId(1), 0, "A stack", "A stack"),
          StructureNode.Test(NodeId(3), NodeId(2), 0, "pop", "- should pop"),
          StructureNode.Suite(NodeId(4), Some(NodeId(1)), Some(1), "QueueSpec", "example.QueueSpec", "example.QueueSpec")
        )
        TestEventLogEncoder.structure(nodes) shouldBe
          """{"item":"structure","nodes":[""" +
          """{"id":1,"kind":"suite","name":"StackSpec","suiteId":"example.StackSpec","className":"example.StackSpec"},""" +
          """{"id":2,"kind":"scope","parent":1,"position":0,"name":"A stack","label":"A stack"},""" +
          """{"id":3,"kind":"test","parent":2,"position":0,"name":"pop","label":"- should pop"},""" +
          """{"id":4,"kind":"suite","parent":1,"position":1,"name":"QueueSpec","suiteId":"example.QueueSpec","className":"example.QueueSpec"}""" +
          """]}""" + "\n"
      }
      "write the root of a further structure, a nested suite new to the event log, with its parent and no position" in {
        val nodes = Vector(
          StructureNode.Suite(NodeId(5), Some(NodeId(1)), None, "HelperSpec", "example.HelperSpec", "example.HelperSpec"),
          StructureNode.Test(NodeId(6), NodeId(5), 0, "helps", "- helps")
        )
        TestEventLogEncoder.structure(nodes) shouldBe
          """{"item":"structure","nodes":[""" +
          """{"id":5,"kind":"suite","parent":1,"name":"HelperSpec","suiteId":"example.HelperSpec","className":"example.HelperSpec"},""" +
          """{"id":6,"kind":"test","parent":5,"position":0,"name":"helps","label":"- helps"}""" +
          """]}""" + "\n"
      }
    }

    "writing the runEnded event" should {
      "write a completed run's outcome as completed" in {
        TestEventLogEncoder.runEnded(RunOutcome.Completed) shouldBe """{"item":"runEnded","outcome":"completed"}""" + "\n"
      }
      "write an aborted run's outcome as aborted" in {
        TestEventLogEncoder.runEnded(RunOutcome.Aborted) shouldBe """{"item":"runEnded","outcome":"aborted"}""" + "\n"
      }
      "write a stopped run's outcome as stopped" in {
        TestEventLogEncoder.runEnded(RunOutcome.Stopped) shouldBe """{"item":"runEnded","outcome":"stopped"}""" + "\n"
      }
    }

    "writing a ScalaTest event" should {

      "leave node and execution out of an event assigned to the run" in {
        TestEventLogEncoder.event(DiscoveryStarting(ordinal, ConfigMap.empty, "main", timeStamp), None) shouldBe
          """{"item":"event","event":"DiscoveryStarting","threadName":"main","timeStamp":1700000000000}""" + "\n"
      }

      "write node and execution, without parentExecution, for an event assigned to a suite" in {
        val suiteStarting = SuiteStarting(
          ordinal = ordinal,
          suiteName = "StackSpec",
          suiteId = "example.StackSpec",
          suiteClassName = Some("example.StackSpec"),
          location = Some(TopOfClass("example.StackSpec")),
          threadName = "main",
          timeStamp = timeStamp
        )
        TestEventLogEncoder.event(suiteStarting, suiteExecution) shouldBe
          """{"item":"event","node":1,"execution":1,"event":"SuiteStarting",""" +
          """"location":{"kind":"TopOfClass","className":"example.StackSpec"},"threadName":"main","timeStamp":1700000000000}""" + "\n"
      }

      "write node, execution and parentExecution for an event assigned to a scope or test" in {
        val testStarting = TestStarting(
          ordinal = ordinal,
          suiteName = "StackSpec",
          suiteId = "example.StackSpec",
          suiteClassName = Some("example.StackSpec"),
          testName = "A stack should pop",
          testText = "should pop",
          location = Some(LineInFile(12, "StackSpec.scala", Some("/src/StackSpec.scala"))),
          threadName = "main",
          timeStamp = timeStamp
        )
        TestEventLogEncoder.event(testStarting, testExecution) shouldBe
          """{"item":"event","node":3,"execution":2,"parentExecution":1,"event":"TestStarting",""" +
          """"location":{"kind":"LineInFile","lineNumber":12,"fileName":"StackSpec.scala","filePathname":"/src/StackSpec.scala"},""" +
          """"threadName":"main","timeStamp":1700000000000}""" + "\n"
      }

      "leave out the ordinal, formatter, rerunner, payload, recorded events and the names its node already gives" in {
        val recorded = InfoProvided(ordinal, "recorded", Some(nameInfo), None, None, None, None, "main", timeStamp)
        val testSucceeded = TestSucceeded(
          ordinal = ordinal,
          suiteName = "StackSpec",
          suiteId = "example.StackSpec",
          suiteClassName = Some("example.StackSpec"),
          testName = "A stack should pop",
          testText = "should pop",
          recordedEvents = Vector(recorded),
          duration = Some(5L),
          formatter = Some(IndentedText("- should pop", "should pop", 1)),
          location = Some(LineInFile(12, "StackSpec.scala", None)),
          rerunner = Some("example.StackSpec"),
          payload = Some("payload"),
          threadName = "main",
          timeStamp = timeStamp
        )
        TestEventLogEncoder.event(testSucceeded, testExecution) shouldBe
          """{"item":"event","node":3,"execution":2,"parentExecution":1,"event":"TestSucceeded","duration":5,""" +
          """"location":{"kind":"LineInFile","lineNumber":12,"fileName":"StackSpec.scala"},""" +
          """"threadName":"main","timeStamp":1700000000000}""" + "\n"
      }

      "write a config map as an object of each value's toString, its keys in ascending String order" in {
        val runStarting = RunStarting(
          ordinal = ordinal,
          testCount = 3,
          configMap = ConfigMap("zeta" -> 1, "alpha" -> List("a", "b"), "Beta" -> "text"),
          threadName = "main",
          timeStamp = timeStamp
        )
        TestEventLogEncoder.event(runStarting, None) shouldBe
          """{"item":"event","event":"RunStarting","testCount":3,""" +
          """"configMap":{"Beta":"text","alpha":"List(a, b)","zeta":"1"},"threadName":"main","timeStamp":1700000000000}""" + "\n"
      }

      "write an empty config map as an empty object" in {
        TestEventLogEncoder.event(RunStarting(ordinal, 0, ConfigMap.empty, None, None, None, "main", timeStamp), None) shouldBe
          """{"item":"event","event":"RunStarting","testCount":0,"configMap":{},"threadName":"main","timeStamp":1700000000000}""" + "\n"
      }

      "leave out the key of an empty sequence and of an absent value" in {
        val testFailed = TestFailed(
          ordinal = ordinal,
          message = "1 did not equal 2",
          suiteName = "StackSpec",
          suiteId = "example.StackSpec",
          suiteClassName = Some("example.StackSpec"),
          testName = "A stack should pop",
          testText = "should pop",
          recordedEvents = Vector.empty,
          analysis = Vector.empty,
          threadName = "main",
          timeStamp = timeStamp
        )
        TestEventLogEncoder.event(testFailed, testExecution) shouldBe
          """{"item":"event","node":3,"execution":2,"parentExecution":1,"event":"TestFailed","message":"1 did not equal 2",""" +
          """"threadName":"main","timeStamp":1700000000000}""" + "\n"
      }

      val eventsWithEveryKeptField: Seq[((Event, Option[NodeExecution]), String)] = Seq(
        (TestFailed(
          ordinal, "1 did not equal 2", "StackSpec", "example.StackSpec", Some("example.StackSpec"), "A stack should pop", "should pop",
          Vector.empty, Vector("first line", "second line"), throwable = boom, duration = Some(7L),
          location = Some(SeeStackDepthException), threadName = "main", timeStamp = timeStamp
        ), testExecution) ->
          (testEventText + """"TestFailed","message":"1 did not equal 2","analysis":["first line","second line"],""" + boomText +
            """"duration":7,"location":{"kind":"SeeStackDepthException"},""" + threadAndTimeText),
        (TestIgnored(
          ordinal, "StackSpec", "example.StackSpec", Some("example.StackSpec"), "A stack should pop", "should pop",
          location = Some(TopOfMethod("example.StackSpec", "public void example.StackSpec.pop()")), threadName = "main",
          timeStamp = timeStamp
        ), testExecution) ->
          (testEventText + """"TestIgnored",""" +
            """"location":{"kind":"TopOfMethod","className":"example.StackSpec","methodId":"public void example.StackSpec.pop()"},""" +
            threadAndTimeText),
        (TestPending(
          ordinal, "StackSpec", "example.StackSpec", Some("example.StackSpec"), "A stack should pop", "should pop",
          Vector.empty, duration = Some(7L), location = lineInFile, threadName = "main", timeStamp = timeStamp
        ), testExecution) ->
          (testEventText + """"TestPending","duration":7,""" + lineInFileText + threadAndTimeText),
        (TestCanceled(
          ordinal, "the database was down", "StackSpec", "example.StackSpec", Some("example.StackSpec"), "A stack should pop", "should pop",
          Vector.empty, throwable = boom, duration = Some(7L), location = lineInFile, threadName = "main", timeStamp = timeStamp
        ), testExecution) ->
          (testEventText + """"TestCanceled","message":"the database was down",""" + boomText + """"duration":7,""" +
            lineInFileText + threadAndTimeText),
        (SuiteCompleted(
          ordinal, "StackSpec", "example.StackSpec", Some("example.StackSpec"), duration = Some(7L), location = lineInFile,
          threadName = "main", timeStamp = timeStamp
        ), suiteExecution) ->
          (suiteEventText + """"SuiteCompleted","duration":7,""" + lineInFileText + threadAndTimeText),
        (SuiteAborted(
          ordinal, "the suite aborted", "StackSpec", "example.StackSpec", Some("example.StackSpec"), throwable = boom,
          duration = Some(7L), location = lineInFile, threadName = "main", timeStamp = timeStamp
        ), suiteExecution) ->
          (suiteEventText + """"SuiteAborted","message":"the suite aborted",""" + boomText + """"duration":7,""" +
            lineInFileText + threadAndTimeText),
        (RunStarting(
          ordinal, 3, ConfigMap("key" -> "value"), location = lineInFile, threadName = "main", timeStamp = timeStamp
        ), None) ->
          (runEventText + """"RunStarting","testCount":3,"configMap":{"key":"value"},""" + lineInFileText + threadAndTimeText),
        (RunCompleted(
          ordinal, Some(7L), Some(summary), location = lineInFile, threadName = "main", timeStamp = timeStamp
        ), None) ->
          (runEventText + """"RunCompleted","duration":7,""" + summaryText + lineInFileText + threadAndTimeText),
        (RunStopped(
          ordinal, Some(7L), Some(summary), location = lineInFile, threadName = "main", timeStamp = timeStamp
        ), None) ->
          (runEventText + """"RunStopped","duration":7,""" + summaryText + lineInFileText + threadAndTimeText),
        (RunAborted(
          ordinal, "the run aborted", boom, Some(7L), Some(summary), location = lineInFile, threadName = "main",
          timeStamp = timeStamp
        ), None) ->
          (runEventText + """"RunAborted","message":"the run aborted",""" + boomText + """"duration":7,""" + summaryText +
            lineInFileText + threadAndTimeText),
        (InfoProvided(
          ordinal, "some info", Some(nameInfo), throwable = boom, location = lineInFile, threadName = "main", timeStamp = timeStamp
        ), testExecution) ->
          (testEventText + """"InfoProvided","message":"some info",""" + boomText + lineInFileText + threadAndTimeText),
        (AlertProvided(
          ordinal, "an alert", Some(nameInfo), throwable = boom, location = lineInFile, threadName = "main", timeStamp = timeStamp
        ), testExecution) ->
          (testEventText + """"AlertProvided","message":"an alert",""" + boomText + lineInFileText + threadAndTimeText),
        (NoteProvided(
          ordinal, "a note", Some(nameInfo), throwable = boom, location = lineInFile, threadName = "main", timeStamp = timeStamp
        ), testExecution) ->
          (testEventText + """"NoteProvided","message":"a note",""" + boomText + lineInFileText + threadAndTimeText),
        (MarkupProvided(
          ordinal, "*markup*", Some(nameInfo), location = lineInFile, threadName = "main", timeStamp = timeStamp
        ), testExecution) ->
          (testEventText + """"MarkupProvided","text":"*markup*",""" + lineInFileText + threadAndTimeText),
        (ScopeOpened(
          ordinal, "A stack", nameInfo, location = lineInFile, threadName = "main", timeStamp = timeStamp
        ), testExecution) ->
          (testEventText + """"ScopeOpened",""" + lineInFileText + threadAndTimeText),
        (ScopeClosed(
          ordinal, "A stack", nameInfo, location = lineInFile, threadName = "main", timeStamp = timeStamp
        ), testExecution) ->
          (testEventText + """"ScopeClosed",""" + lineInFileText + threadAndTimeText),
        (ScopePending(
          ordinal, "A stack", nameInfo, location = lineInFile, threadName = "main", timeStamp = timeStamp
        ), testExecution) ->
          (testEventText + """"ScopePending",""" + lineInFileText + threadAndTimeText),
        (DiscoveryCompleted(ordinal, Some(7L), "main", timeStamp), None) ->
          (runEventText + """"DiscoveryCompleted","duration":7,""" + threadAndTimeText)
      )

      eventsWithEveryKeptField.foreach { case ((event, execution), expectedLine) =>
        s"write every field the format keeps of a ${event.getClass.getSimpleName}, in its class's order" in {
          TestEventLogEncoder.event(event, execution) shouldBe expectedLine + "\n"
        }
      }
    }

    "writing a string" should {
      "escape a quotation mark, a backslash and every control character, so that the event stays on one line" in {
        infoLine("say \"hi\" \\ \b \f \n \r \t \u0000 \u001F end") shouldBe
          """{"item":"event","event":"InfoProvided","message":"say \"hi\" \\ \b \f \n \r \t \u0000 \u001F end",""" +
          """"threadName":"main","timeStamp":1700000000000}""" + "\n"
      }
      "escape a lone surrogate and write a surrogate pair and other non-ASCII characters as they are" in {
        infoLine("high \uD83D low \uDE00 pair 😀 é") shouldBe
          """{"item":"event","event":"InfoProvided","message":"high \uD83D low \uDE00 pair """ + "😀 é" +
          """","threadName":"main","timeStamp":1700000000000}""" + "\n"
      }
      "escape a high surrogate that ends the string" in {
        infoLine("ends \uD83D") shouldBe
          """{"item":"event","event":"InfoProvided","message":"ends \uD83D","threadName":"main","timeStamp":1700000000000}""" + "\n"
      }
    }

    "writing a throwable" should {

      "write its class name, message, frames, cause and suppressed throwables in order, numbering each in the order written" in {
        val cause = withoutStackTrace(new IllegalStateException("inner"))
        val failure = new IllegalArgumentException("outer", cause)
        failure.addSuppressed(withoutStackTrace(new RuntimeException(null: String)))
        failure.addSuppressed(withoutStackTrace(new UnsupportedOperationException("suppressed later")))
        failure.setStackTrace(
          Array(
            new StackTraceElement("example.Stack", "pop", "Stack.scala", 17),
            new StackTraceElement("example.Native", "call", null, -2),
            new StackTraceElement("example.Unknown", "run", null, -1)
          )
        )
        suiteAbortedBy(failure) shouldBe suiteAbortedLine(
          """{"id":1,"className":"java.lang.IllegalArgumentException","message":"outer","stackTrace":[""" +
            """{"className":"example.Stack","methodName":"pop","fileName":"Stack.scala","lineNumber":17,"text":"example.Stack.pop(Stack.scala:17)"},""" +
            """{"className":"example.Native","methodName":"call","isNative":true,"text":"example.Native.call(Native Method)"},""" +
            """{"className":"example.Unknown","methodName":"run","text":"example.Unknown.run(Unknown Source)"}],""" +
            """"cause":{"id":2,"className":"java.lang.IllegalStateException","message":"inner"},""" +
            """"suppressed":[{"id":3,"className":"java.lang.RuntimeException"},""" +
            """{"id":4,"className":"java.lang.UnsupportedOperationException","message":"suppressed later"}]}"""
        )
      }

      "write a chain of 10000 causes whole, each inside the one before" in {
        val length = 10000
        val innermost = withoutStackTrace(new RuntimeException(s"cause $length"))
        val outermost = (length - 1 to 1 by -1).foldLeft(innermost) { (cause, number) =>
          withoutStackTrace(new RuntimeException(s"cause $number", cause))
        }
        val expectedThrowableText =
          (1 to length)
            .map(number => s"""{"id":$number,"className":"java.lang.RuntimeException","message":"cause $number"""")
            .mkString(""","cause":""") + "}" * length
        suiteAbortedBy(outermost) shouldBe suiteAbortedLine(expectedThrowableText)
      }

      "write a throwable the event has already written as a ref to its id" in {
        val shared = withoutStackTrace(new IllegalStateException("shared"))
        val failure = withoutStackTrace(new RuntimeException("outer", shared))
        failure.addSuppressed(shared)
        suiteAbortedBy(failure) shouldBe suiteAbortedLine(
          """{"id":1,"className":"java.lang.RuntimeException","message":"outer",""" +
            """"cause":{"id":2,"className":"java.lang.IllegalStateException","message":"shared"},"suppressed":[{"ref":2}]}"""
        )
      }

      "end a cycle of causes with a ref" in {
        val first = withoutStackTrace(new RuntimeException("first"))
        val second = withoutStackTrace(new RuntimeException("second", first))
        first.initCause(second)
        suiteAbortedBy(first) shouldBe suiteAbortedLine(
          """{"id":1,"className":"java.lang.RuntimeException","message":"first",""" +
            """"cause":{"id":2,"className":"java.lang.RuntimeException","message":"second","cause":{"ref":1}}}"""
        )
      }

      "write in full, not as a ref, a different throwable that equals one already written" in {
        val cause = withoutStackTrace(new EqualToEveryThrowable)
        val failure = withoutStackTrace(new EqualToEveryThrowable)
        failure.initCause(cause)
        suiteAbortedBy(failure) shouldBe suiteAbortedLine(
          """{"id":1,"className":"org.scalatest.events.EqualToEveryThrowable","message":"same",""" +
            """"cause":{"id":2,"className":"org.scalatest.events.EqualToEveryThrowable","message":"same"}}"""
        )
      }

      "number throwables afresh in each event" in {
        val failure = withoutStackTrace(new RuntimeException("again"))
        val expectedLine = suiteAbortedLine("""{"id":1,"className":"java.lang.RuntimeException","message":"again"}""")
        suiteAbortedBy(failure) shouldBe expectedLine
        suiteAbortedBy(failure) shouldBe expectedLine
      }

      "write the failed code's file and line number, and the frames, of a StackDepth exception" in {
        val failure = new TestFailedException(
          (_: StackDepthException) => Some("1 did not equal 2"),
          None,
          Position("StackSpec.scala", "/src/StackSpec.scala", 14)
        )
        failure.setStackTrace(Array(new StackTraceElement("example.StackSpec", "pop", "StackSpec.scala", 14)))
        suiteAbortedBy(failure) shouldBe suiteAbortedLine(
          """{"id":1,"className":"org.scalatest.exceptions.TestFailedException","message":"1 did not equal 2",""" +
            """"stackDepth":{"fileName":"StackSpec.scala","filePathname":"/src/StackSpec.scala","lineNumber":14},""" +
            """"stackTrace":[{"className":"example.StackSpec","methodName":"pop","fileName":"StackSpec.scala","lineNumber":14,""" +
            """"text":"example.StackSpec.pop(StackSpec.scala:14)"}]}"""
        )
      }

      "mark a PropertyCheckFailedException as a property check" in {
        val failure = withoutStackTrace(
          new GeneratorDrivenPropertyCheckFailedException(
            (_: StackDepthException) => "the property was falsified",
            None,
            Left(Position("StackSpec.scala", "/src/StackSpec.scala", 20)),
            None,
            "undecorated",
            List(1),
            None,
            List.empty
          )
        )
        suiteAbortedBy(failure) shouldBe suiteAbortedLine(
          """{"id":1,"className":"org.scalatest.exceptions.GeneratorDrivenPropertyCheckFailedException",""" +
            """"message":"the property was falsified",""" +
            """"stackDepth":{"fileName":"StackSpec.scala","filePathname":"/src/StackSpec.scala","lineNumber":20},"propertyCheck":true}"""
        )
      }
    }
  }
}
