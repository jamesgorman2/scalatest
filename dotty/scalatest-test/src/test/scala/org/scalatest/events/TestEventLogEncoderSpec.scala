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
  private val stackSpec = NodeId(Vector(NodePlace.Suite(SuiteId("example.StackSpec"), 0)))
  private val aStack = NodeId(stackSpec.places :+ NodePlace.Scope("A stack", 0))
  private val pop = NodeId(stackSpec.places :+ NodePlace.Test(TestName("A stack should pop")))
  private val stackSpecText = """[{"suiteId":"example.StackSpec","index":0}]"""
  private val aStackText = """[{"suiteId":"example.StackSpec","index":0},{"scope":"A stack","index":0}]"""
  private val popText = """[{"suiteId":"example.StackSpec","index":0},{"test":"A stack should pop"}]"""
  private val suiteExecution = Some(NodeExecution(stackSpec, Vector(1)))
  private val testExecution = Some(NodeExecution(pop, Vector(1, 3, 2)))
  private val lineInFile = Some(LineInFile(12, "StackSpec.scala", None))

  private val boomText = """"throwable":{"id":1,"className":"java.lang.IllegalStateException","message":"boom"},"""
  private val summaryText =
    """"summary":{"testsSucceededCount":1,"testsFailedCount":2,"testsIgnoredCount":3,"testsPendingCount":4,""" +
      """"testsCanceledCount":5,"suitesCompletedCount":6,"suitesAbortedCount":7,"scopesPendingCount":8},"""
  private val lineInFileText = """"location":{"kind":"LineInFile","lineNumber":12,"fileName":"StackSpec.scala"},"""
  private val threadAndTimeText = """"threadName":"main","timeStamp":1700000000000}"""
  private val testEventText = s"""{"item":"event","node":$popText,"executions":[1,3,2],"event":"""
  private val suiteEventText = s"""{"item":"event","node":$stackSpecText,"executions":[1],"event":"""
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
    suiteEventText + """"SuiteAborted","message":"aborted","throwable":""" + throwableText +
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
        val queueSpec = NodeId(stackSpec.places :+ NodePlace.Suite(SuiteId("example.QueueSpec"), 0))
        val nodes = Vector(
          StructureNode.Suite(stackSpec, None, None, "StackSpec", SuiteId("example.StackSpec"), "example.StackSpec"),
          StructureNode.Scope(aStack, stackSpec, 0, "A stack", "A stack"),
          StructureNode.Test(pop, aStack, 0, "pop", "- should pop"),
          StructureNode.Suite(queueSpec, Some(stackSpec), Some(1), "QueueSpec", SuiteId("example.QueueSpec"), "example.QueueSpec")
        )
        TestEventLogEncoder.structure(nodes) shouldBe
          """{"item":"structure","nodes":[""" +
          s"""{"id":$stackSpecText,"kind":"suite","name":"StackSpec","suiteId":"example.StackSpec","className":"example.StackSpec"},""" +
          s"""{"id":$aStackText,"kind":"scope","parent":$stackSpecText,"position":0,"name":"A stack","label":"A stack"},""" +
          s"""{"id":$popText,"kind":"test","parent":$aStackText,"position":0,"name":"pop","label":"- should pop"},""" +
          """{"id":[{"suiteId":"example.StackSpec","index":0},{"suiteId":"example.QueueSpec","index":0}],"kind":"suite",""" +
          s""""parent":$stackSpecText,"position":1,"name":"QueueSpec","suiteId":"example.QueueSpec","className":"example.QueueSpec"}""" +
          """]}""" + "\n"
      }
      "write ignored as true, after its label, on a test the structure marks ignored" in {
        TestEventLogEncoder.structure(Vector(StructureNode.Test(pop, aStack, 0, "pop", "- should pop", ignored = true))) shouldBe
          """{"item":"structure","nodes":[""" +
          s"""{"id":$popText,"kind":"test","parent":$aStackText,"position":0,"name":"pop","label":"- should pop","ignored":true}""" +
          """]}""" + "\n"
      }
      "write each place of a node path below the places above it, with a scope's or suite's index" in {
        val secondHelper = NodeId(pop.places :+ NodePlace.Suite(SuiteId("example.HelperSpec"), 1))
        val inner = NodeId(secondHelper.places :+ NodePlace.Scope("A helper", 2))
        val nodes = Vector(
          StructureNode.Suite(secondHelper, Some(pop), None, "HelperSpec", SuiteId("example.HelperSpec"), "example.HelperSpec"),
          StructureNode.Scope(inner, secondHelper, 0, "A helper", "A helper")
        )
        val secondHelperText =
          """[{"suiteId":"example.StackSpec","index":0},{"test":"A stack should pop"},{"suiteId":"example.HelperSpec","index":1}]"""
        TestEventLogEncoder.structure(nodes) shouldBe
          """{"item":"structure","nodes":[""" +
          s"""{"id":$secondHelperText,"kind":"suite","parent":$popText,""" +
          """"name":"HelperSpec","suiteId":"example.HelperSpec","className":"example.HelperSpec"},""" +
          """{"id":[{"suiteId":"example.StackSpec","index":0},{"test":"A stack should pop"},""" +
          """{"suiteId":"example.HelperSpec","index":1},{"scope":"A helper","index":2}],""" +
          s""""kind":"scope","parent":$secondHelperText,"position":0,"name":"A helper","label":"A helper"}""" +
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

      "leave node and executions out of an event assigned to the run" in {
        TestEventLogEncoder.event(DiscoveryStarting(ordinal, ConfigMap.empty, "main", timeStamp), None) shouldBe
          """{"item":"event","event":"DiscoveryStarting","threadName":"main","timeStamp":1700000000000}""" + "\n"
      }

      "write the suite's node path and its one execution index for an event assigned to a top-level suite" in {
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
          suiteEventText + """"SuiteStarting",""" +
          """"location":{"kind":"TopOfClass","className":"example.StackSpec"},"threadName":"main","timeStamp":1700000000000}""" + "\n"
      }

      "write the node path and the execution path, scopes included, for an event assigned to a test" in {
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
          testEventText + """"TestStarting",""" +
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
          testEventText + """"TestSucceeded","duration":5,""" +
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
          testEventText + """"TestFailed","message":"1 did not equal 2",""" +
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
