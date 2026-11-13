package org.scalatest.events

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class TestEventLogDecoderSpec extends AnyWordSpec with Matchers {

  private val timeStamp = 1700000000000L
  private val summary = Summary(1, 2, 3, 4, 5, 6, 7, 8)
  private val suiteExecution = Some(NodeExecution(NodeId(1), 1, None))
  private val testExecution = Some(NodeExecution(NodeId(3), 2, Some(1)))

  private val runStartedLine = """{"item":"runStarted","pid":4242,"startTime":"2026-11-13T02:54:37.123456Z"}"""

  private val structureLine =
    """{"item":"structure","nodes":[""" +
      """{"id":1,"kind":"suite","name":"StackSpec","suiteId":"example.StackSpec","className":"example.StackSpec"},""" +
      """{"id":2,"kind":"scope","parent":1,"position":0,"name":"A stack","label":"A stack"},""" +
      """{"id":3,"kind":"test","parent":2,"position":0,"name":"pop","label":"- should pop"},""" +
      """{"id":4,"kind":"suite","parent":1,"position":1,"name":"QueueSpec","suiteId":"example.QueueSpec","className":"example.QueueSpec"}""" +
      """]}"""

  private val runEndedLine = """{"item":"runEnded","outcome":"completed"}"""

  private def suiteAbortedLine(throwableText: String): String =
    """{"item":"event","node":1,"execution":1,"event":"SuiteAborted","message":"aborted","throwable":""" + throwableText +
      ""","threadName":"main","timeStamp":1700000000000}"""

  private val throwableWithCauseAndSuppressedLine = suiteAbortedLine(
    """{"id":1,"className":"java.lang.IllegalArgumentException","message":"outer","stackTrace":[""" +
      """{"className":"example.Stack","methodName":"pop","fileName":"Stack.scala","lineNumber":17,"text":"example.Stack.pop(Stack.scala:17)"},""" +
      """{"className":"example.Native","methodName":"call","isNative":true,"text":"example.Native.call(Native Method)"},""" +
      """{"className":"example.Unknown","methodName":"run","text":"example.Unknown.run(Unknown Source)"}],""" +
      """"cause":{"id":2,"className":"java.lang.IllegalStateException","message":"inner"},""" +
      """"suppressed":[{"id":3,"className":"java.lang.RuntimeException"}]}"""
  )

  private def throwableIn(line: String): DecodedThrowable = {
    TestEventLogDecoder.decode(line) match {
      case Right(TestEventLogEvent.ScalaTestEvent(_, DecodedEvent.SuiteAborted(_, Some(throwable), _, _, _, _))) => throwable
      case other => fail(s"the line did not decode to a SuiteAborted with a throwable: $other")
    }
  }

  private def infoMessageIn(line: String): String = {
    TestEventLogDecoder.decode(line) match {
      case Right(TestEventLogEvent.ScalaTestEvent(_, DecodedEvent.InfoProvided(message, _, _, _, _))) => message
      case other => fail(s"the line did not decode to an InfoProvided: $other")
    }
  }

  private def infoLine(messageText: String): String =
    """{"item":"event","event":"InfoProvided","message":"""" + messageText + """","threadName":"main","timeStamp":1700000000000}"""

  "The test event log decoder" when {

    "reading the runStarted event" should {
      "yield its PID and start time" in {
        TestEventLogDecoder.decode(runStartedLine) shouldBe
          Right(TestEventLogEvent.RunStarted(4242L, "2026-11-13T02:54:37.123456Z"))
      }
    }

    "reading a structure event" should {
      "yield a top-level suite without parent or position, then its nodes in the order written" in {
        TestEventLogDecoder.decode(structureLine) shouldBe Right(
          TestEventLogEvent.Structure(
            Vector(
              StructureNode.Suite(NodeId(1), None, None, "StackSpec", "example.StackSpec", "example.StackSpec"),
              StructureNode.Scope(NodeId(2), NodeId(1), 0, "A stack", "A stack"),
              StructureNode.Test(NodeId(3), NodeId(2), 0, "pop", "- should pop"),
              StructureNode.Suite(NodeId(4), Some(NodeId(1)), Some(1), "QueueSpec", "example.QueueSpec", "example.QueueSpec")
            )
          )
        )
      }
      "yield a further structure whose root is a nested suite new to the event log with its parent and no position" in {
        val line =
          """{"item":"structure","nodes":[""" +
            """{"id":5,"kind":"suite","parent":1,"name":"HelperSpec","suiteId":"example.HelperSpec","className":"example.HelperSpec"},""" +
            """{"id":6,"kind":"test","parent":5,"position":0,"name":"helps","label":"- helps"}""" +
            """]}"""
        TestEventLogDecoder.decode(line) shouldBe Right(
          TestEventLogEvent.Structure(
            Vector(
              StructureNode.Suite(NodeId(5), Some(NodeId(1)), None, "HelperSpec", "example.HelperSpec", "example.HelperSpec"),
              StructureNode.Test(NodeId(6), NodeId(5), 0, "helps", "- helps")
            )
          )
        )
      }
    }

    "reading the runEnded event" should {
      "yield a completed run" in {
        TestEventLogDecoder.decode(runEndedLine) shouldBe Right(TestEventLogEvent.RunEnded(RunOutcome.Completed))
      }
      "yield an aborted run" in {
        TestEventLogDecoder.decode("""{"item":"runEnded","outcome":"aborted"}""") shouldBe
          Right(TestEventLogEvent.RunEnded(RunOutcome.Aborted))
      }
      "yield a stopped run" in {
        TestEventLogDecoder.decode("""{"item":"runEnded","outcome":"stopped"}""") shouldBe
          Right(TestEventLogEvent.RunEnded(RunOutcome.Stopped))
      }
    }

    "reading a ScalaTest event" should {

      "yield no node or execution for an event assigned to the run" in {
        TestEventLogDecoder.decode(
          """{"item":"event","event":"DiscoveryStarting","threadName":"main","timeStamp":1700000000000}"""
        ) shouldBe Right(TestEventLogEvent.ScalaTestEvent(None, DecodedEvent.DiscoveryStarting("main", timeStamp)))
      }

      "yield an absent value for an omitted key and an empty sequence for an omitted array" in {
        TestEventLogDecoder.decode(
          """{"item":"event","node":3,"execution":2,"parentExecution":1,"event":"TestFailed","message":"1 did not equal 2",""" +
            """"threadName":"main","timeStamp":1700000000000}"""
        ) shouldBe Right(
          TestEventLogEvent.ScalaTestEvent(
            testExecution,
            DecodedEvent.TestFailed("1 did not equal 2", Vector.empty, None, None, None, "main", timeStamp)
          )
        )
      }

      "yield a config map's values as the strings written" in {
        TestEventLogDecoder.decode(
          """{"item":"event","event":"RunStarting","testCount":3,""" +
            """"configMap":{"Beta":"text","alpha":"List(a, b)","zeta":"1"},"threadName":"main","timeStamp":1700000000000}"""
        ) shouldBe Right(
          TestEventLogEvent.ScalaTestEvent(
            None,
            DecodedEvent.RunStarting(3, Map("Beta" -> "text", "alpha" -> "List(a, b)", "zeta" -> "1"), None, "main", timeStamp)
          )
        )
      }

      val linesOfEveryOtherEventClass: Seq[(String, TestEventLogEvent)] = Seq(
        ("""{"item":"event","node":1,"execution":1,"event":"SuiteStarting",""" +
          """"location":{"kind":"TopOfClass","className":"example.StackSpec"},"threadName":"main","timeStamp":1700000000000}""") ->
          TestEventLogEvent.ScalaTestEvent(
            suiteExecution,
            DecodedEvent.SuiteStarting(Some(TopOfClass("example.StackSpec")), "main", timeStamp)
          ),
        ("""{"item":"event","node":3,"execution":2,"parentExecution":1,"event":"TestStarting",""" +
          """"location":{"kind":"LineInFile","lineNumber":12,"fileName":"StackSpec.scala","filePathname":"/src/StackSpec.scala"},""" +
          """"threadName":"main","timeStamp":1700000000000}""") ->
          TestEventLogEvent.ScalaTestEvent(
            testExecution,
            DecodedEvent.TestStarting(Some(LineInFile(12, "StackSpec.scala", Some("/src/StackSpec.scala"))), "main", timeStamp)
          ),
        ("""{"item":"event","node":3,"execution":2,"parentExecution":1,"event":"TestSucceeded","duration":5,""" +
          """"location":{"kind":"LineInFile","lineNumber":12,"fileName":"StackSpec.scala"},""" +
          """"threadName":"main","timeStamp":1700000000000}""") ->
          TestEventLogEvent.ScalaTestEvent(
            testExecution,
            DecodedEvent.TestSucceeded(Some(5L), Some(LineInFile(12, "StackSpec.scala", None)), "main", timeStamp)
          ),
        ("""{"item":"event","node":3,"execution":2,"parentExecution":1,"event":"TestFailed","message":"1 did not equal 2",""" +
          """"analysis":["first line","second line"],"duration":7,"location":{"kind":"SeeStackDepthException"},""" +
          """"threadName":"main","timeStamp":1700000000000}""") ->
          TestEventLogEvent.ScalaTestEvent(
            testExecution,
            DecodedEvent.TestFailed(
              "1 did not equal 2", Vector("first line", "second line"), None, Some(7L), Some(SeeStackDepthException), "main", timeStamp
            )
          ),
        ("""{"item":"event","node":3,"execution":2,"parentExecution":1,"event":"TestIgnored",""" +
          """"location":{"kind":"TopOfMethod","className":"example.StackSpec","methodId":"public void example.StackSpec.pop()"},""" +
          """"threadName":"main","timeStamp":1700000000000}""") ->
          TestEventLogEvent.ScalaTestEvent(
            testExecution,
            DecodedEvent.TestIgnored(
              Some(TopOfMethod("example.StackSpec", "public void example.StackSpec.pop()")), "main", timeStamp
            )
          ),
        """{"item":"event","node":3,"execution":2,"parentExecution":1,"event":"TestPending","duration":7,"threadName":"main","timeStamp":1700000000000}""" ->
          TestEventLogEvent.ScalaTestEvent(testExecution, DecodedEvent.TestPending(Some(7L), None, "main", timeStamp)),
        ("""{"item":"event","node":3,"execution":2,"parentExecution":1,"event":"TestCanceled","message":"the database was down",""" +
          """"duration":7,"threadName":"main","timeStamp":1700000000000}""") ->
          TestEventLogEvent.ScalaTestEvent(
            testExecution,
            DecodedEvent.TestCanceled("the database was down", None, Some(7L), None, "main", timeStamp)
          ),
        """{"item":"event","node":1,"execution":1,"event":"SuiteCompleted","duration":7,"threadName":"main","timeStamp":1700000000000}""" ->
          TestEventLogEvent.ScalaTestEvent(suiteExecution, DecodedEvent.SuiteCompleted(Some(7L), None, "main", timeStamp)),
        ("""{"item":"event","node":1,"execution":1,"event":"SuiteAborted","message":"the suite aborted",""" +
          """"duration":7,"threadName":"main","timeStamp":1700000000000}""") ->
          TestEventLogEvent.ScalaTestEvent(
            suiteExecution,
            DecodedEvent.SuiteAborted("the suite aborted", None, Some(7L), None, "main", timeStamp)
          ),
        ("""{"item":"event","event":"RunCompleted","duration":7,""" +
          """"summary":{"testsSucceededCount":1,"testsFailedCount":2,"testsIgnoredCount":3,"testsPendingCount":4,""" +
          """"testsCanceledCount":5,"suitesCompletedCount":6,"suitesAbortedCount":7,"scopesPendingCount":8},""" +
          """"threadName":"main","timeStamp":1700000000000}""") ->
          TestEventLogEvent.ScalaTestEvent(None, DecodedEvent.RunCompleted(Some(7L), Some(summary), None, "main", timeStamp)),
        ("""{"item":"event","event":"RunStopped","duration":7,""" +
          """"summary":{"testsSucceededCount":1,"testsFailedCount":2,"testsIgnoredCount":3,"testsPendingCount":4,""" +
          """"testsCanceledCount":5,"suitesCompletedCount":6,"suitesAbortedCount":7,"scopesPendingCount":8},""" +
          """"threadName":"main","timeStamp":1700000000000}""") ->
          TestEventLogEvent.ScalaTestEvent(None, DecodedEvent.RunStopped(Some(7L), Some(summary), None, "main", timeStamp)),
        ("""{"item":"event","event":"RunAborted","message":"the run aborted","duration":7,""" +
          """"summary":{"testsSucceededCount":1,"testsFailedCount":2,"testsIgnoredCount":3,"testsPendingCount":4,""" +
          """"testsCanceledCount":5,"suitesCompletedCount":6,"suitesAbortedCount":7,"scopesPendingCount":8},""" +
          """"threadName":"main","timeStamp":1700000000000}""") ->
          TestEventLogEvent.ScalaTestEvent(
            None,
            DecodedEvent.RunAborted("the run aborted", None, Some(7L), Some(summary), None, "main", timeStamp)
          ),
        """{"item":"event","node":3,"execution":2,"parentExecution":1,"event":"InfoProvided","message":"some info","threadName":"main","timeStamp":1700000000000}""" ->
          TestEventLogEvent.ScalaTestEvent(testExecution, DecodedEvent.InfoProvided("some info", None, None, "main", timeStamp)),
        """{"item":"event","node":3,"execution":2,"parentExecution":1,"event":"AlertProvided","message":"an alert","threadName":"main","timeStamp":1700000000000}""" ->
          TestEventLogEvent.ScalaTestEvent(testExecution, DecodedEvent.AlertProvided("an alert", None, None, "main", timeStamp)),
        """{"item":"event","node":3,"execution":2,"parentExecution":1,"event":"NoteProvided","message":"a note","threadName":"main","timeStamp":1700000000000}""" ->
          TestEventLogEvent.ScalaTestEvent(testExecution, DecodedEvent.NoteProvided("a note", None, None, "main", timeStamp)),
        """{"item":"event","node":3,"execution":2,"parentExecution":1,"event":"MarkupProvided","text":"*markup*","threadName":"main","timeStamp":1700000000000}""" ->
          TestEventLogEvent.ScalaTestEvent(testExecution, DecodedEvent.MarkupProvided("*markup*", None, "main", timeStamp)),
        """{"item":"event","node":3,"execution":2,"parentExecution":1,"event":"ScopeOpened","threadName":"main","timeStamp":1700000000000}""" ->
          TestEventLogEvent.ScalaTestEvent(testExecution, DecodedEvent.ScopeOpened(None, "main", timeStamp)),
        """{"item":"event","node":3,"execution":2,"parentExecution":1,"event":"ScopeClosed","threadName":"main","timeStamp":1700000000000}""" ->
          TestEventLogEvent.ScalaTestEvent(testExecution, DecodedEvent.ScopeClosed(None, "main", timeStamp)),
        """{"item":"event","node":3,"execution":2,"parentExecution":1,"event":"ScopePending","threadName":"main","timeStamp":1700000000000}""" ->
          TestEventLogEvent.ScalaTestEvent(testExecution, DecodedEvent.ScopePending(None, "main", timeStamp)),
        """{"item":"event","event":"DiscoveryCompleted","duration":7,"threadName":"main","timeStamp":1700000000000}""" ->
          TestEventLogEvent.ScalaTestEvent(None, DecodedEvent.DiscoveryCompleted(Some(7L), "main", timeStamp))
      )

      linesOfEveryOtherEventClass.foreach { case (line, expectedEvent) =>
        val eventName = expectedEvent match {
          case TestEventLogEvent.ScalaTestEvent(_, event) => event.productPrefix
          case other => other.productPrefix
        }
        s"yield a $eventName with its node, its executions and the fields the format keeps" in {
          TestEventLogDecoder.decode(line) shouldBe Right(expectedEvent)
        }
      }
    }

    "reading a string" should {
      "yield each escaped character" in {
        infoMessageIn(infoLine("""say \"hi\" \\ \/ \b \f \n \r \t \u0000 \u001f end""")) shouldBe
          "say \"hi\" \\ / \b \f \n \r \t \u0000 \u001F end"
      }
      "yield a lone surrogate's escape as the same char" in {
        infoMessageIn(infoLine("""high \uD83D low \uDE00 end""")).toVector shouldBe
          "high \uD83D low \uDE00 end".toVector
      }
      "yield an escaped surrogate pair and an unescaped one as the same two chars" in {
        infoMessageIn(infoLine("""😀 """ + "😀")).toVector shouldBe "😀 😀".toVector
      }
    }

    "reading an integer" should {
      "yield one a Double cannot hold exactly" in {
        TestEventLogDecoder.decode(
          """{"item":"event","event":"DiscoveryStarting","threadName":"main","timeStamp":9007199254740993}"""
        ) shouldBe Right(TestEventLogEvent.ScalaTestEvent(None, DecodedEvent.DiscoveryStarting("main", 9007199254740993L)))
      }
      "yield the largest and the smallest Long" in {
        TestEventLogDecoder.decode("""{"item":"runStarted","pid":9223372036854775807,"startTime":"t"}""") shouldBe
          Right(TestEventLogEvent.RunStarted(Long.MaxValue, "t"))
        TestEventLogDecoder.decode("""{"item":"runStarted","pid":-9223372036854775808,"startTime":"t"}""") shouldBe
          Right(TestEventLogEvent.RunStarted(Long.MinValue, "t"))
      }
    }

    "reading a throwable" should {

      "yield its class name, message and frames" in {
        val throwable = throwableIn(throwableWithCauseAndSuppressedLine)
        throwable.className shouldBe "java.lang.IllegalArgumentException"
        throwable.message shouldBe Some("outer")
        throwable.stackDepth shouldBe None
        throwable.isPropertyCheckFailure shouldBe false
        throwable.stackTrace shouldBe Vector(
          DecodedStackFrame("example.Stack", "pop", Some("Stack.scala"), Some(17), false, "example.Stack.pop(Stack.scala:17)"),
          DecodedStackFrame("example.Native", "call", None, None, true, "example.Native.call(Native Method)"),
          DecodedStackFrame("example.Unknown", "run", None, None, false, "example.Unknown.run(Unknown Source)")
        )
      }

      "yield its cause" in {
        val cause = throwableIn(throwableWithCauseAndSuppressedLine).cause.getOrElse(fail("the throwable has no cause"))
        cause.className shouldBe "java.lang.IllegalStateException"
        cause.message shouldBe Some("inner")
        cause.stackTrace shouldBe Vector.empty
        cause.cause shouldBe None
        cause.suppressed shouldBe Vector.empty
      }

      "yield its suppressed throwables, one without a message having none" in {
        val suppressed = throwableIn(throwableWithCauseAndSuppressedLine).suppressed
        suppressed.map(_.className) shouldBe Vector("java.lang.RuntimeException")
        suppressed.map(_.message) shouldBe Vector(None)
      }

      "yield the same object for a ref as for the throwable it names" in {
        val throwable = throwableIn(
          suiteAbortedLine(
            """{"id":1,"className":"java.lang.RuntimeException","message":"outer",""" +
              """"cause":{"id":2,"className":"java.lang.IllegalStateException","message":"shared"},"suppressed":[{"ref":2}]}"""
          )
        )
        val cause = throwable.cause.getOrElse(fail("the throwable has no cause"))
        throwable.suppressed should have size 1
        throwable.suppressed.head should be theSameInstanceAs cause
      }

      "yield a cycle of causes as a cycle" in {
        val first = throwableIn(
          suiteAbortedLine(
            """{"id":1,"className":"java.lang.RuntimeException","message":"first",""" +
              """"cause":{"id":2,"className":"java.lang.RuntimeException","message":"second","cause":{"ref":1}}}"""
          )
        )
        val second = first.cause.getOrElse(fail("the first throwable has no cause"))
        second.message shouldBe Some("second")
        second.cause.getOrElse(fail("the second throwable has no cause")) should be theSameInstanceAs first
      }

      "yield different objects for throwables with different ids and the same content" in {
        val throwable = throwableIn(
          suiteAbortedLine(
            """{"id":1,"className":"example.Same","message":"same","cause":{"id":2,"className":"example.Same","message":"same"}}"""
          )
        )
        throwable.cause.getOrElse(fail("the throwable has no cause")) should not be theSameInstanceAs(throwable)
      }

      "yield the failed code's file and line number of a StackDepth exception" in {
        val throwable = throwableIn(
          suiteAbortedLine(
            """{"id":1,"className":"org.scalatest.exceptions.TestFailedException","message":"1 did not equal 2",""" +
              """"stackDepth":{"fileName":"StackSpec.scala","filePathname":"/src/StackSpec.scala","lineNumber":14}}"""
          )
        )
        throwable.stackDepth shouldBe Some(DecodedStackDepth(Some("StackSpec.scala"), Some("/src/StackSpec.scala"), Some(14)))
      }

      "yield a StackDepth exception with no file or line number defined" in {
        val throwable = throwableIn(
          suiteAbortedLine("""{"id":1,"className":"org.scalatest.exceptions.TestFailedException","stackDepth":{}}""")
        )
        throwable.stackDepth shouldBe Some(DecodedStackDepth(None, None, None))
      }

      "yield a property check failure where the throwable is marked as one" in {
        val throwable = throwableIn(
          suiteAbortedLine(
            """{"id":1,"className":"org.scalatest.exceptions.GeneratorDrivenPropertyCheckFailedException",""" +
              """"message":"the property was falsified","stackDepth":{},"propertyCheck":true}"""
          )
        )
        throwable.isPropertyCheckFailure shouldBe true
      }
    }

    "given a line holding a key the format does not give its object" should {
      val linesAndUnknownKeys = Seq(
        """{"item":"runStarted","pid":1,"startTime":"t","extra":1}""" -> "extra",
        """{"item":"runEnded","outcome":"completed","duration":1}""" -> "duration",
        """{"item":"structure","nodes":[{"id":1,"kind":"suite","name":"S","label":"S","suiteId":"s","className":"c"}]}""" -> "label",
        """{"item":"structure","nodes":[{"id":2,"kind":"test","parent":1,"position":0,"name":"t","label":"- t","suiteId":"s"}]}""" -> "suiteId",
        """{"item":"structure","nodes":[{"id":2,"kind":"scope","parent":1,"position":0,"name":"t","label":"t","className":"c"}]}""" -> "className",
        """{"item":"event","event":"DiscoveryStarting","ordinal":1,"threadName":"main","timeStamp":1}""" -> "ordinal",
        """{"item":"event","event":"DiscoveryStarting","location":{"kind":"SeeStackDepthException"},"threadName":"main","timeStamp":1}""" -> "location",
        """{"item":"event","node":1,"execution":1,"parentExecution":1,"event":"ScopeOpened","message":"A stack","threadName":"main","timeStamp":1}""" -> "message",
        """{"item":"event","node":1,"execution":1,"event":"SuiteStarting","suiteName":"S","threadName":"main","timeStamp":1}""" -> "suiteName",
        """{"item":"event","event":"RunCompleted","location":{"kind":"TopOfClass","className":"c","methodId":"m"},"threadName":"main","timeStamp":1}""" -> "methodId",
        ("""{"item":"event","event":"RunCompleted","summary":{"testsSucceededCount":1,"testsFailedCount":2,"testsIgnoredCount":3,""" +
          """"testsPendingCount":4,"testsCanceledCount":5,"suitesCompletedCount":6,"suitesAbortedCount":7,"scopesPendingCount":8,""" +
          """"testsCompletedCount":3},"threadName":"main","timeStamp":1}""") -> "testsCompletedCount",
        suiteAbortedLine("""{"id":1,"className":"c","depth":3}""") -> "depth",
        suiteAbortedLine("""{"id":1,"className":"c","cause":{"ref":1,"id":2}}""") -> "id",
        suiteAbortedLine("""{"id":1,"className":"c","stackDepth":{"depth":3}}""") -> "depth",
        suiteAbortedLine("""{"id":1,"className":"c","stackTrace":[{"className":"c","methodName":"m","text":"c.m","moduleName":"x"}]}""") -> "moduleName"
      )
      linesAndUnknownKeys.zipWithIndex.foreach { case ((line, key), index) =>
        s"report that it cannot be decoded, naming the key $key (line ${index + 1})" in {
          TestEventLogDecoder.decode(line) shouldBe Left(s"unknown key $key")
        }
      }
    }

    "given a line lacking a required key" should {
      val linesAndMissingKeys = Seq(
        """{"pid":1,"startTime":"t"}""" -> "item",
        """{"item":"runStarted","startTime":"t"}""" -> "pid",
        """{"item":"runStarted","pid":1}""" -> "startTime",
        """{"item":"runEnded"}""" -> "outcome",
        """{"item":"structure"}""" -> "nodes",
        """{"item":"structure","nodes":[{"kind":"suite","name":"S","suiteId":"s","className":"c"}]}""" -> "id",
        """{"item":"structure","nodes":[{"id":1,"name":"S","suiteId":"s","className":"c"}]}""" -> "kind",
        """{"item":"structure","nodes":[{"id":1,"kind":"suite","suiteId":"s","className":"c"}]}""" -> "name",
        """{"item":"structure","nodes":[{"id":1,"kind":"suite","name":"S","className":"c"}]}""" -> "suiteId",
        """{"item":"structure","nodes":[{"id":1,"kind":"suite","name":"S","suiteId":"s"}]}""" -> "className",
        """{"item":"structure","nodes":[{"id":2,"kind":"test","position":0,"name":"t","label":"- t"}]}""" -> "parent",
        """{"item":"structure","nodes":[{"id":2,"kind":"test","parent":1,"name":"t","label":"- t"}]}""" -> "position",
        """{"item":"structure","nodes":[{"id":2,"kind":"scope","parent":1,"position":0,"name":"t"}]}""" -> "label",
        """{"item":"event","threadName":"main","timeStamp":1}""" -> "event",
        """{"item":"event","execution":1,"event":"SuiteStarting","threadName":"main","timeStamp":1}""" -> "node",
        """{"item":"event","parentExecution":1,"event":"SuiteStarting","threadName":"main","timeStamp":1}""" -> "node",
        """{"item":"event","node":1,"event":"SuiteStarting","threadName":"main","timeStamp":1}""" -> "execution",
        """{"item":"event","event":"DiscoveryStarting","timeStamp":1}""" -> "threadName",
        """{"item":"event","event":"DiscoveryStarting","threadName":"main"}""" -> "timeStamp",
        """{"item":"event","node":3,"execution":1,"parentExecution":1,"event":"TestFailed","threadName":"main","timeStamp":1}""" -> "message",
        """{"item":"event","node":3,"execution":1,"parentExecution":1,"event":"MarkupProvided","threadName":"main","timeStamp":1}""" -> "text",
        """{"item":"event","event":"RunStarting","configMap":{},"threadName":"main","timeStamp":1}""" -> "testCount",
        """{"item":"event","event":"RunStarting","testCount":1,"threadName":"main","timeStamp":1}""" -> "configMap",
        """{"item":"event","event":"RunCompleted","location":{"className":"c"},"threadName":"main","timeStamp":1}""" -> "kind",
        """{"item":"event","event":"RunCompleted","location":{"kind":"LineInFile","lineNumber":1},"threadName":"main","timeStamp":1}""" -> "fileName",
        """{"item":"event","event":"RunCompleted","location":{"kind":"TopOfMethod","className":"c"},"threadName":"main","timeStamp":1}""" -> "methodId",
        ("""{"item":"event","event":"RunCompleted","summary":{"testsSucceededCount":1,"testsFailedCount":2,"testsIgnoredCount":3,""" +
          """"testsPendingCount":4,"testsCanceledCount":5,"suitesCompletedCount":6,"suitesAbortedCount":7},""" +
          """"threadName":"main","timeStamp":1}""") -> "scopesPendingCount",
        suiteAbortedLine("""{"className":"c"}""") -> "id",
        suiteAbortedLine("""{"id":1}""") -> "className",
        suiteAbortedLine("""{"id":1,"className":"c","stackTrace":[{"className":"c","methodName":"m"}]}""") -> "text"
      )
      linesAndMissingKeys.zipWithIndex.foreach { case ((line, key), index) =>
        s"report that it cannot be decoded, naming the key $key (line ${index + 1})" in {
          TestEventLogDecoder.decode(line) shouldBe Left(s"missing key $key")
        }
      }
    }

    "given a line holding a value the format does not give" should {
      "report an unknown item" in {
        TestEventLogDecoder.decode("""{"item":"progress","pid":1}""") shouldBe Left("unknown item progress")
      }
      "report an unknown event" in {
        TestEventLogDecoder.decode("""{"item":"event","event":"TestExploded","threadName":"main","timeStamp":1}""") shouldBe
          Left("unknown event TestExploded")
      }
      "report an unknown kind of node" in {
        TestEventLogDecoder.decode("""{"item":"structure","nodes":[{"id":1,"kind":"group","name":"g"}]}""") shouldBe
          Left("unknown kind group")
      }
      "report an unknown kind of location" in {
        TestEventLogDecoder.decode(
          """{"item":"event","event":"RunCompleted","location":{"kind":"Somewhere"},"threadName":"main","timeStamp":1}"""
        ) shouldBe Left("unknown kind Somewhere")
      }
      "report an unknown outcome" in {
        TestEventLogDecoder.decode("""{"item":"runEnded","outcome":"paused"}""") shouldBe Left("unknown outcome paused")
      }
    }

    "given a line that is not one JSON object" should {
      val descriptionsAndLines = Seq(
        "an empty line" -> "",
        "a blank line" -> "  ",
        "an array" -> "[]",
        "a string" -> "\"runStarted\"",
        "a number" -> "42",
        "a boolean" -> "true",
        "null" -> "null",
        "two objects" -> (runEndedLine + runEndedLine),
        "an object followed by other text" -> (runEndedLine + " x"),
        "an object that is not closed" -> """{"item":"runEnded","outcome":"completed"""",
        "an object with a trailing comma" -> """{"item":"runEnded","outcome":"completed",}""",
        "an object with an unquoted key" -> """{item:"runEnded","outcome":"completed"}""",
        "an object with a repeated key" -> """{"item":"runEnded","item":"runEnded","outcome":"completed"}""",
        "an object with a null value" -> """{"item":"runEnded","outcome":null}""",
        "a string with an unescaped control character" -> ("""{"item":"runEnded","outcome":"comp""" + "\t" + """leted"}"""),
        "a string with an unknown escape" -> """{"item":"runEnded","outcome":"\x"}""",
        "a string with a short unicode escape" -> """{"item":"runEnded","outcome":"\u12"}""",
        "a number with a leading zero" -> """{"item":"runStarted","pid":01,"startTime":"t"}""",
        "arrays nested without end" -> ("[" * 100000)
      )
      descriptionsAndLines.foreach { case (description, line) =>
        s"report that $description cannot be decoded" in {
          TestEventLogDecoder.decode(line).isLeft shouldBe true
        }
      }
    }

    "given a line holding a value of the wrong type" should {
      val descriptionsAndLines = Seq(
        "an integer written as a string" -> """{"item":"runStarted","pid":"1","startTime":"t"}""",
        "an integer written with a fraction" -> """{"item":"runStarted","pid":1.0,"startTime":"t"}""",
        "an integer written with an exponent" -> """{"item":"runStarted","pid":1e3,"startTime":"t"}""",
        "an integer larger than a Long" -> """{"item":"runStarted","pid":9223372036854775808,"startTime":"t"}""",
        "a position larger than an Int" ->
          """{"item":"structure","nodes":[{"id":2,"kind":"test","parent":1,"position":2147483648,"name":"t","label":"- t"}]}""",
        "a string written as a number" -> """{"item":"event","event":"DiscoveryStarting","threadName":7,"timeStamp":1}""",
        "nodes written as an object" -> """{"item":"structure","nodes":{}}""",
        "a node written as a string" -> """{"item":"structure","nodes":["suite"]}""",
        "an analysis line written as a number" ->
          """{"item":"event","node":3,"execution":1,"parentExecution":1,"event":"TestFailed","message":"m","analysis":[1],"threadName":"main","timeStamp":1}""",
        "a config map value written as a number" ->
          """{"item":"event","event":"RunStarting","testCount":1,"configMap":{"key":1},"threadName":"main","timeStamp":1}""",
        "a property check mark written as a string" -> suiteAbortedLine("""{"id":1,"className":"c","propertyCheck":"true"}"""),
        "a ref to a throwable the event does not hold" -> suiteAbortedLine("""{"id":1,"className":"c","cause":{"ref":5}}"""),
        "a ref to a throwable the event holds later" ->
          suiteAbortedLine("""{"id":1,"className":"c","cause":{"ref":2},"suppressed":[{"id":2,"className":"c"}]}"""),
        "two throwables with one id" -> suiteAbortedLine("""{"id":1,"className":"c","cause":{"id":1,"className":"c"}}""")
      )
      descriptionsAndLines.foreach { case (description, line) =>
        s"report that $description cannot be decoded" in {
          TestEventLogDecoder.decode(line).isLeft shouldBe true
        }
      }
    }

    "given text whose last line is torn" should {
      "yield the event of each whole line and no event from the torn line" in {
        val tornLine = throwableWithCauseAndSuppressedLine.take(throwableWithCauseAndSuppressedLine.length / 2)
        val text = runStartedLine + "\n" + structureLine + "\n" + tornLine
        val decoded = text.split('\n').toVector.map(TestEventLogDecoder.decode)
        decoded.map(_.isRight) shouldBe Vector(true, true, false)
      }
      "yield no event from a line torn after any of its characters" in {
        val wholeLines = Seq(runStartedLine, structureLine, throwableWithCauseAndSuppressedLine, runEndedLine)
        val tornLines = wholeLines.flatMap(line => (0 until line.length).map(line.take))
        tornLines.filter(tornLine => TestEventLogDecoder.decode(tornLine).isRight) shouldBe Seq.empty
      }
    }
  }
}
