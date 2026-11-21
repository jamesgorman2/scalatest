package org.scalatest.events

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class TestEventLogDecoderSpec extends AnyWordSpec with Matchers {

  private val timeStamp = 1700000000000L
  private val summary = Summary(1, 2, 3, 4, 5, 6, 7, 8)
  private val stackSpec = NodeId(Vector(NodePlace.Suite(SuiteId("example.StackSpec"), 0)))
  private val aStack = NodeId(stackSpec.places :+ NodePlace.Scope("A stack", 0))
  private val pop = NodeId(stackSpec.places :+ NodePlace.Test(TestName("A stack should pop")))
  private val stackSpecText = """[{"suiteId":"example.StackSpec","index":0}]"""
  private val aStackText = """[{"suiteId":"example.StackSpec","index":0},{"scope":"A stack","index":0}]"""
  private val popText = """[{"suiteId":"example.StackSpec","index":0},{"test":"A stack should pop"}]"""
  private val suiteExecution = Some(NodeExecution(stackSpec, Vector(1)))
  private val testExecution = Some(NodeExecution(pop, Vector(1, 3, 2)))
  private val testEventText = s"""{"item":"event","node":$popText,"executions":[1,3,2],"event":"""
  private val suiteEventText = s"""{"item":"event","node":$stackSpecText,"executions":[1],"event":"""
  private val runEventText = """{"item":"event","event":"""

  private val aSuite = """[{"suiteId":"s","index":0}]"""
  private val aTest = """[{"suiteId":"s","index":0},{"test":"t"}]"""

  private val runStartedLine = """{"item":"runStarted","pid":4242,"startTime":"2026-11-13T02:54:37.123456Z"}"""

  private val structureLine =
    """{"item":"structure","nodes":[""" +
      s"""{"id":$stackSpecText,"kind":"suite","name":"StackSpec","suiteId":"example.StackSpec","className":"example.StackSpec"},""" +
      s"""{"id":$aStackText,"kind":"scope","parent":$stackSpecText,"position":0,"name":"A stack","label":"A stack"},""" +
      s"""{"id":$popText,"kind":"test","parent":$aStackText,"position":0,"name":"pop","label":"- should pop"},""" +
      """{"id":[{"suiteId":"example.StackSpec","index":0},{"suiteId":"example.QueueSpec","index":0}],"kind":"suite",""" +
      s""""parent":$stackSpecText,"position":1,"name":"QueueSpec","suiteId":"example.QueueSpec","className":"example.QueueSpec"}""" +
      """]}"""

  private val runEndedLine = """{"item":"runEnded","outcome":"completed"}"""

  private def suiteAbortedLine(throwableText: String): String =
    suiteEventText + """"SuiteAborted","message":"aborted","throwable":""" + throwableText +
      ""","threadName":"main","timeStamp":1700000000000}"""

  private val throwableWithCauseAndSuppressedLine = suiteAbortedLine(
    """{"id":1,"className":"java.lang.IllegalArgumentException","message":"outer","stackTrace":[""" +
      """{"className":"example.Stack","methodName":"pop","fileName":"Stack.scala","lineNumber":17,"text":"example.Stack.pop(Stack.scala:17)"},""" +
      """{"className":"example.Native","methodName":"call","isNative":true,"text":"example.Native.call(Native Method)"},""" +
      """{"className":"example.Unknown","methodName":"run","text":"example.Unknown.run(Unknown Source)"}],""" +
      """"cause":{"id":2,"className":"java.lang.IllegalStateException","message":"inner"},""" +
      """"suppressed":[{"id":3,"className":"java.lang.RuntimeException"},""" +
      """{"id":4,"className":"java.lang.UnsupportedOperationException","message":"suppressed later"}]}"""
  )

  private val boomText = """"throwable":{"id":1,"className":"java.lang.IllegalStateException","message":"boom"},"""
  private val summaryText =
    """"summary":{"testsSucceededCount":1,"testsFailedCount":2,"testsIgnoredCount":3,"testsPendingCount":4,""" +
      """"testsCanceledCount":5,"suitesCompletedCount":6,"suitesAbortedCount":7,"scopesPendingCount":8},"""
  private val lineInFileText = """"location":{"kind":"LineInFile","lineNumber":12,"fileName":"StackSpec.scala"},"""
  private val threadAndTimeText = """"threadName":"main","timeStamp":1700000000000}"""

  private val lineInFile = Some(LineInFile(12, "StackSpec.scala", None))

  private def boom: Option[DecodedThrowable] =
    Some(new DecodedThrowable("java.lang.IllegalStateException", Some("boom"), None, false, Vector.empty))

  private def comparable(event: TestEventLogEvent): Any = {
    event match {
      case TestEventLogEvent.ScalaTestEvent(execution, decoded) =>
        val fields = decoded.productIterator.map {
          // A DecodedThrowable has no structural equality.
          case Some(throwable: DecodedThrowable) => Some((throwable.className, throwable.message))
          case other => other
        }
        (execution, decoded.productPrefix, fields.toVector)
      case other => other
    }
  }

  private def throwableIn(line: String): DecodedThrowable = {
    TestEventLogDecoder.decode(line) match {
      case TestEventLogEvent.ScalaTestEvent(_, DecodedEvent.SuiteAborted(_, Some(throwable), _, _, _, _)) => throwable
      case other => fail(s"the line did not decode to a SuiteAborted with a throwable: $other")
    }
  }

  private def infoMessageIn(line: String): String = {
    TestEventLogDecoder.decode(line) match {
      case TestEventLogEvent.ScalaTestEvent(_, DecodedEvent.InfoProvided(message, _, _, _, _)) => message
      case other => fail(s"the line did not decode to an InfoProvided: $other")
    }
  }

  private def whyUndecodable(line: String): String =
    intercept[MalformedTestEventLogException](TestEventLogDecoder.decode(line)).reason

  private def isDecodable(line: String): Boolean = {
    try {
      TestEventLogDecoder.decode(line)
      true
    } catch {
      case _: MalformedTestEventLogException => false
    }
  }

  private def infoLine(messageText: String): String =
    """{"item":"event","event":"InfoProvided","message":"""" + messageText + """","threadName":"main","timeStamp":1700000000000}"""

  "The test event log decoder" when {

    "reading the runStarted event" should {
      "yield its PID and start time" in {
        TestEventLogDecoder.decode(runStartedLine) shouldBe
          TestEventLogEvent.RunStarted(4242L, "2026-11-13T02:54:37.123456Z")
      }
    }

    "reading a structure event" should {
      "yield a top-level suite without parent or position, then its nodes in the order written" in {
        TestEventLogDecoder.decode(structureLine) shouldBe TestEventLogEvent.Structure(
          Vector(
            StructureNode.Suite(stackSpec, None, None, "StackSpec", SuiteId("example.StackSpec"), TestClassName("example.StackSpec")),
            StructureNode.Scope(aStack, stackSpec, 0, "A stack", "A stack"),
            StructureNode.Test(pop, aStack, 0, "pop", "- should pop"),
            StructureNode.Suite(
              NodeId(stackSpec.places :+ NodePlace.Suite(SuiteId("example.QueueSpec"), 0)),
              Some(stackSpec),
              Some(1),
              "QueueSpec",
              SuiteId("example.QueueSpec"),
              TestClassName("example.QueueSpec")
            )
          )
        )
      }
      "yield a test the structure marks ignored as ignored" in {
        val line =
          s"""{"item":"structure","nodes":[{"id":$popText,"kind":"test","parent":$aStackText,"position":0,"name":"pop","label":"- should pop","ignored":true}]}"""
        TestEventLogDecoder.decode(line) shouldBe
          TestEventLogEvent.Structure(Vector(StructureNode.Test(pop, aStack, 0, "pop", "- should pop", ignored = true)))
      }
      "yield a test without ignored as not ignored" in {
        val line = s"""{"item":"structure","nodes":[{"id":$popText,"kind":"test","parent":$aStackText,"position":0,"name":"pop","label":"- should pop"}]}"""
        TestEventLogDecoder.decode(line) shouldBe
          TestEventLogEvent.Structure(Vector(StructureNode.Test(pop, aStack, 0, "pop", "- should pop", ignored = false)))
      }
      "yield each place of a node path below the places above it, with a scope's or suite's index" in {
        val secondHelperText =
          """[{"suiteId":"example.StackSpec","index":0},{"test":"A stack should pop"},{"suiteId":"example.HelperSpec","index":1}]"""
        val line =
          """{"item":"structure","nodes":[""" +
            s"""{"id":$secondHelperText,"kind":"suite","parent":$popText,""" +
            """"name":"HelperSpec","suiteId":"example.HelperSpec","className":"example.HelperSpec"},""" +
            """{"id":[{"suiteId":"example.StackSpec","index":0},{"test":"A stack should pop"},""" +
            """{"suiteId":"example.HelperSpec","index":1},{"scope":"A helper","index":2}],""" +
            s""""kind":"scope","parent":$secondHelperText,"position":0,"name":"A helper","label":"A helper"}""" +
            """]}"""
        val secondHelper = NodeId(pop.places :+ NodePlace.Suite(SuiteId("example.HelperSpec"), 1))
        TestEventLogDecoder.decode(line) shouldBe TestEventLogEvent.Structure(
          Vector(
            StructureNode.Suite(secondHelper, Some(pop), None, "HelperSpec", SuiteId("example.HelperSpec"), TestClassName("example.HelperSpec")),
            StructureNode.Scope(NodeId(secondHelper.places :+ NodePlace.Scope("A helper", 2)), secondHelper, 0, "A helper", "A helper")
          )
        )
      }
    }

    "reading the runEnded event" should {
      "yield a completed run" in {
        TestEventLogDecoder.decode(runEndedLine) shouldBe TestEventLogEvent.RunEnded(RunOutcome.Completed)
      }
      "yield an aborted run" in {
        TestEventLogDecoder.decode("""{"item":"runEnded","outcome":"aborted"}""") shouldBe
          TestEventLogEvent.RunEnded(RunOutcome.Aborted)
      }
      "yield a stopped run" in {
        TestEventLogDecoder.decode("""{"item":"runEnded","outcome":"stopped"}""") shouldBe
          TestEventLogEvent.RunEnded(RunOutcome.Stopped)
      }
    }

    "reading a ScalaTest event" should {

      "yield no node or executions for an event assigned to the run" in {
        TestEventLogDecoder.decode(
          """{"item":"event","event":"DiscoveryStarting","threadName":"main","timeStamp":1700000000000}"""
        ) shouldBe TestEventLogEvent.ScalaTestEvent(None, DecodedEvent.DiscoveryStarting("main", timeStamp))
      }

      "yield an absent value for an omitted key and an empty sequence for an omitted array" in {
        TestEventLogDecoder.decode(
          testEventText + """"TestFailed","message":"1 did not equal 2",""" +
            """"threadName":"main","timeStamp":1700000000000}"""
        ) shouldBe TestEventLogEvent.ScalaTestEvent(
          testExecution,
          DecodedEvent.TestFailed("1 did not equal 2", Vector.empty, None, None, None, "main", timeStamp)
        )
      }

      "yield a config map's values as the strings written" in {
        TestEventLogDecoder.decode(
          """{"item":"event","event":"RunStarting","testCount":3,""" +
            """"configMap":{"Beta":"text","alpha":"List(a, b)","zeta":"1"},"threadName":"main","timeStamp":1700000000000}"""
        ) shouldBe TestEventLogEvent.ScalaTestEvent(
          None,
          DecodedEvent.RunStarting(3, Map("Beta" -> "text", "alpha" -> "List(a, b)", "zeta" -> "1"), None, "main", timeStamp)
        )
      }

      val linesHoldingEveryKeyOfAnEventClass: Seq[(String, TestEventLogEvent)] = Seq(
        (suiteEventText + """"SuiteStarting","location":{"kind":"TopOfClass","className":"example.StackSpec"},""" +
          threadAndTimeText) ->
          TestEventLogEvent.ScalaTestEvent(
            suiteExecution,
            DecodedEvent.SuiteStarting(Some(TopOfClass("example.StackSpec")), "main", timeStamp)
          ),
        (testEventText + """"TestStarting",""" +
          """"location":{"kind":"LineInFile","lineNumber":12,"fileName":"StackSpec.scala","filePathname":"/src/StackSpec.scala"},""" +
          threadAndTimeText) ->
          TestEventLogEvent.ScalaTestEvent(
            testExecution,
            DecodedEvent.TestStarting(Some(LineInFile(12, "StackSpec.scala", Some("/src/StackSpec.scala"))), "main", timeStamp)
          ),
        (testEventText + """"TestSucceeded","duration":5,""" + lineInFileText + threadAndTimeText) ->
          TestEventLogEvent.ScalaTestEvent(testExecution, DecodedEvent.TestSucceeded(Some(5L), lineInFile, "main", timeStamp)),
        (testEventText + """"TestFailed","message":"1 did not equal 2","analysis":["first line","second line"],""" + boomText +
          """"duration":7,"location":{"kind":"SeeStackDepthException"},""" + threadAndTimeText) ->
          TestEventLogEvent.ScalaTestEvent(
            testExecution,
            DecodedEvent.TestFailed(
              "1 did not equal 2", Vector("first line", "second line"), boom, Some(7L), Some(SeeStackDepthException), "main", timeStamp
            )
          ),
        (testEventText + """"TestIgnored",""" +
          """"location":{"kind":"TopOfMethod","className":"example.StackSpec","methodId":"public void example.StackSpec.pop()"},""" +
          threadAndTimeText) ->
          TestEventLogEvent.ScalaTestEvent(
            testExecution,
            DecodedEvent.TestIgnored(
              Some(TopOfMethod("example.StackSpec", "public void example.StackSpec.pop()")), "main", timeStamp
            )
          ),
        (testEventText + """"TestPending","duration":7,""" + lineInFileText + threadAndTimeText) ->
          TestEventLogEvent.ScalaTestEvent(testExecution, DecodedEvent.TestPending(Some(7L), lineInFile, "main", timeStamp)),
        (testEventText + """"TestCanceled","message":"the database was down",""" + boomText + """"duration":7,""" +
          lineInFileText + threadAndTimeText) ->
          TestEventLogEvent.ScalaTestEvent(
            testExecution,
            DecodedEvent.TestCanceled("the database was down", boom, Some(7L), lineInFile, "main", timeStamp)
          ),
        (suiteEventText + """"SuiteCompleted","duration":7,""" + lineInFileText + threadAndTimeText) ->
          TestEventLogEvent.ScalaTestEvent(suiteExecution, DecodedEvent.SuiteCompleted(Some(7L), lineInFile, "main", timeStamp)),
        (suiteEventText + """"SuiteAborted","message":"the suite aborted",""" + boomText + """"duration":7,""" +
          lineInFileText + threadAndTimeText) ->
          TestEventLogEvent.ScalaTestEvent(
            suiteExecution,
            DecodedEvent.SuiteAborted("the suite aborted", boom, Some(7L), lineInFile, "main", timeStamp)
          ),
        (runEventText + """"RunStarting","testCount":3,"configMap":{"key":"value"},""" + lineInFileText + threadAndTimeText) ->
          TestEventLogEvent.ScalaTestEvent(
            None,
            DecodedEvent.RunStarting(3, Map("key" -> "value"), lineInFile, "main", timeStamp)
          ),
        (runEventText + """"RunCompleted","duration":7,""" + summaryText + lineInFileText + threadAndTimeText) ->
          TestEventLogEvent.ScalaTestEvent(None, DecodedEvent.RunCompleted(Some(7L), Some(summary), lineInFile, "main", timeStamp)),
        (runEventText + """"RunStopped","duration":7,""" + summaryText + lineInFileText + threadAndTimeText) ->
          TestEventLogEvent.ScalaTestEvent(None, DecodedEvent.RunStopped(Some(7L), Some(summary), lineInFile, "main", timeStamp)),
        (runEventText + """"RunAborted","message":"the run aborted",""" + boomText + """"duration":7,""" + summaryText +
          lineInFileText + threadAndTimeText) ->
          TestEventLogEvent.ScalaTestEvent(
            None,
            DecodedEvent.RunAborted("the run aborted", boom, Some(7L), Some(summary), lineInFile, "main", timeStamp)
          ),
        (testEventText + """"InfoProvided","message":"some info",""" + boomText + lineInFileText + threadAndTimeText) ->
          TestEventLogEvent.ScalaTestEvent(testExecution, DecodedEvent.InfoProvided("some info", boom, lineInFile, "main", timeStamp)),
        (testEventText + """"AlertProvided","message":"an alert",""" + boomText + lineInFileText + threadAndTimeText) ->
          TestEventLogEvent.ScalaTestEvent(testExecution, DecodedEvent.AlertProvided("an alert", boom, lineInFile, "main", timeStamp)),
        (testEventText + """"NoteProvided","message":"a note",""" + boomText + lineInFileText + threadAndTimeText) ->
          TestEventLogEvent.ScalaTestEvent(testExecution, DecodedEvent.NoteProvided("a note", boom, lineInFile, "main", timeStamp)),
        (testEventText + """"MarkupProvided","text":"*markup*",""" + lineInFileText + threadAndTimeText) ->
          TestEventLogEvent.ScalaTestEvent(testExecution, DecodedEvent.MarkupProvided("*markup*", lineInFile, "main", timeStamp)),
        (testEventText + """"ScopeOpened",""" + lineInFileText + threadAndTimeText) ->
          TestEventLogEvent.ScalaTestEvent(testExecution, DecodedEvent.ScopeOpened(lineInFile, "main", timeStamp)),
        (testEventText + """"ScopeClosed",""" + lineInFileText + threadAndTimeText) ->
          TestEventLogEvent.ScalaTestEvent(testExecution, DecodedEvent.ScopeClosed(lineInFile, "main", timeStamp)),
        (testEventText + """"ScopePending",""" + lineInFileText + threadAndTimeText) ->
          TestEventLogEvent.ScalaTestEvent(testExecution, DecodedEvent.ScopePending(lineInFile, "main", timeStamp)),
        (runEventText + """"DiscoveryCompleted","duration":7,""" + threadAndTimeText) ->
          TestEventLogEvent.ScalaTestEvent(None, DecodedEvent.DiscoveryCompleted(Some(7L), "main", timeStamp))
      )

      linesHoldingEveryKeyOfAnEventClass.foreach { case (line, expectedEvent) =>
        val eventName = expectedEvent match {
          case TestEventLogEvent.ScalaTestEvent(_, event) => event.productPrefix
          case other => other.productPrefix
        }
        s"yield a $eventName with its node, its executions and every field the format keeps" in {
          comparable(TestEventLogDecoder.decode(line)) shouldBe comparable(expectedEvent)
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
        infoMessageIn(infoLine("""\uD83D\uDE00 """ + "😀")).toVector shouldBe "😀 😀".toVector
      }
    }

    "reading an integer" should {
      "yield one a Double cannot hold exactly" in {
        TestEventLogDecoder.decode(
          """{"item":"event","event":"DiscoveryStarting","threadName":"main","timeStamp":9007199254740993}"""
        ) shouldBe TestEventLogEvent.ScalaTestEvent(None, DecodedEvent.DiscoveryStarting("main", 9007199254740993L))
      }
      "yield the largest and the smallest Long" in {
        TestEventLogDecoder.decode("""{"item":"runStarted","pid":9223372036854775807,"startTime":"1970-01-01T00:00:00Z"}""") shouldBe
          TestEventLogEvent.RunStarted(Long.MaxValue, "1970-01-01T00:00:00Z")
        TestEventLogDecoder.decode("""{"item":"runStarted","pid":-9223372036854775808,"startTime":"1970-01-01T00:00:00Z"}""") shouldBe
          TestEventLogEvent.RunStarted(Long.MinValue, "1970-01-01T00:00:00Z")
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

      "yield its suppressed throwables in the order written, and no message for one written without a message" in {
        val suppressed = throwableIn(throwableWithCauseAndSuppressedLine).suppressed
        suppressed.map(_.className) shouldBe Vector("java.lang.RuntimeException", "java.lang.UnsupportedOperationException")
        suppressed.map(_.message) shouldBe Vector(None, Some("suppressed later"))
      }

      "yield a chain of 10000 causes whole, each the cause of the one before" in {
        val length = 10000
        val throwableText =
          (1 to length)
            .map(number => s"""{"id":$number,"className":"java.lang.RuntimeException","message":"cause $number"""")
            .mkString(""","cause":""") + "}" * length
        val outermost = throwableIn(suiteAbortedLine(throwableText))
        val chain = Iterator.iterate(Option(outermost))(_.flatMap(_.cause)).takeWhile(_.nonEmpty).flatten.toVector
        chain.map(_.message) shouldBe (1 to length).map(number => Some(s"cause $number"))
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
        """{"item":"runStarted","pid":1,"startTime":"1970-01-01T00:00:00Z","extra":1}""" -> ("extra", "the runStarted event"),
        """{"item":"runEnded","outcome":"completed","duration":1}""" -> ("duration", "the runEnded event"),
        s"""{"item":"structure","nodes":[{"id":$aSuite,"kind":"suite","name":"S","label":"S","suiteId":"s","className":"c"}]}""" -> ("label", "a suite node"),
        s"""{"item":"structure","nodes":[{"id":$aTest,"kind":"test","parent":$aSuite,"position":0,"name":"t","label":"- t","suiteId":"s"}]}""" -> ("suiteId", "a test node"),
        s"""{"item":"structure","nodes":[{"id":$aTest,"kind":"scope","parent":$aSuite,"position":0,"name":"t","label":"t","className":"c"}]}""" -> ("className", "a scope node"),
        s"""{"item":"structure","nodes":[{"id":$aTest,"kind":"scope","parent":$aSuite,"position":0,"name":"t","label":"t","ignored":true}]}""" -> ("ignored", "a scope node"),
        s"""{"item":"structure","nodes":[{"id":$aSuite,"kind":"suite","name":"S","suiteId":"s","className":"c","ignored":true}]}""" -> ("ignored", "a suite node"),
        """{"item":"structure","nodes":[{"id":[{"suiteId":"s","index":0,"scope":"t"}],"kind":"suite","name":"S","suiteId":"s","className":"c"}]}""" -> ("scope", "a suite's place"),
        """{"item":"structure","nodes":[{"id":[{"scope":"t","index":0,"test":"t"}],"kind":"suite","name":"S","suiteId":"s","className":"c"}]}""" -> ("test", "a scope's place"),
        s"""{"item":"structure","nodes":[{"id":$aSuite,"kind":"suite","parent":[{"test":"t","index":0}],"name":"S","suiteId":"s","className":"c"}]}""" -> ("index", "a test's place"),
        """{"item":"event","event":"DiscoveryStarting","ordinal":1,"threadName":"main","timeStamp":1}""" -> ("ordinal", "a ScalaTest event"),
        """{"item":"event","event":"DiscoveryStarting","location":{"kind":"SeeStackDepthException"},"threadName":"main","timeStamp":1}""" -> ("location", "a DiscoveryStarting event"),
        s"""{"item":"event","node":$aTest,"executions":[1,1],"event":"ScopeOpened","message":"A stack","threadName":"main","timeStamp":1}""" -> ("message", "a ScopeOpened event"),
        s"""{"item":"event","node":$aSuite,"executions":[1],"event":"SuiteStarting","suiteName":"S","threadName":"main","timeStamp":1}""" -> ("suiteName", "a SuiteStarting event"),
        """{"item":"event","event":"RunCompleted","location":{"kind":"TopOfClass","className":"c","methodId":"m"},"threadName":"main","timeStamp":1}""" -> ("methodId", "a TopOfClass location"),
        ("""{"item":"event","event":"RunCompleted","summary":{"testsSucceededCount":1,"testsFailedCount":2,"testsIgnoredCount":3,""" +
          """"testsPendingCount":4,"testsCanceledCount":5,"suitesCompletedCount":6,"suitesAbortedCount":7,"scopesPendingCount":8,""" +
          """"testsCompletedCount":3},"threadName":"main","timeStamp":1}""") -> ("testsCompletedCount", "a summary"),
        suiteAbortedLine("""{"id":1,"className":"c","depth":3}""") -> ("depth", "a throwable"),
        suiteAbortedLine("""{"id":1,"className":"c","cause":{"ref":1,"id":2}}""") -> ("id", "a ref to a throwable"),
        suiteAbortedLine("""{"id":1,"className":"c","stackDepth":{"depth":3}}""") -> ("depth", "a throwable's stackDepth"),
        suiteAbortedLine("""{"id":1,"className":"c","stackTrace":[{"className":"c","methodName":"m","text":"c.m","moduleName":"x"}]}""") -> ("moduleName", "a stack frame")
      )
      linesAndUnknownKeys.foreach { case (line, (key, description)) =>
        s"report the unknown key $key of $description" in {
          whyUndecodable(line) shouldBe s"unknown key $key"
        }
      }
    }

    "given a line lacking a required key" should {
      val linesAndMissingKeys = Seq(
        """{"pid":1,"startTime":"1970-01-01T00:00:00Z"}""" -> ("item", "any event"),
        """{"item":"runStarted","startTime":"1970-01-01T00:00:00Z"}""" -> ("pid", "the runStarted event"),
        """{"item":"runStarted","pid":1}""" -> ("startTime", "the runStarted event"),
        """{"item":"runEnded"}""" -> ("outcome", "the runEnded event"),
        """{"item":"structure"}""" -> ("nodes", "a structure event"),
        """{"item":"structure","nodes":[{"kind":"suite","name":"S","suiteId":"s","className":"c"}]}""" -> ("id", "a suite node"),
        """{"item":"structure","nodes":[{"id":[],"kind":"suite","name":"S","suiteId":"s","className":"c"}]}""" -> ("id", "a suite node whose id is empty"),
        s"""{"item":"structure","nodes":[{"id":$aSuite,"name":"S","suiteId":"s","className":"c"}]}""" -> ("kind", "a node"),
        s"""{"item":"structure","nodes":[{"id":$aSuite,"kind":"suite","suiteId":"s","className":"c"}]}""" -> ("name", "a suite node"),
        s"""{"item":"structure","nodes":[{"id":$aSuite,"kind":"suite","name":"S","className":"c"}]}""" -> ("suiteId", "a suite node"),
        s"""{"item":"structure","nodes":[{"id":$aSuite,"kind":"suite","name":"S","suiteId":"s"}]}""" -> ("className", "a suite node"),
        """{"item":"structure","nodes":[{"id":[{"suiteId":"s","index":0},{"suiteId":"n","index":0}],"kind":"suite","name":"N","suiteId":"n","className":"c"}]}""" -> ("parent", "a nested suite node"),
        """{"item":"structure","nodes":[{"id":[{"suiteId":"s"}],"kind":"suite","name":"S","suiteId":"s","className":"c"}]}""" -> ("index", "a suite's place"),
        """{"item":"structure","nodes":[{"id":[{"suiteId":"s","index":0},{"scope":"t"}],"kind":"scope","parent":[{"suiteId":"s","index":0}],"position":0,"name":"t","label":"t"}]}""" -> ("index", "a scope's place"),
        s"""{"item":"structure","nodes":[{"id":$aTest,"kind":"test","position":0,"name":"t","label":"- t"}]}""" -> ("parent", "a test node"),
        s"""{"item":"structure","nodes":[{"id":$aTest,"kind":"test","parent":[],"position":0,"name":"t","label":"- t"}]}""" -> ("parent", "a test node whose parent is empty"),
        s"""{"item":"structure","nodes":[{"id":$aTest,"kind":"test","parent":$aSuite,"name":"t","label":"- t"}]}""" -> ("position", "a test node"),
        s"""{"item":"structure","nodes":[{"id":$aTest,"kind":"scope","parent":$aSuite,"position":0,"name":"t"}]}""" -> ("label", "a scope node"),
        """{"item":"event","threadName":"main","timeStamp":1}""" -> ("event", "a ScalaTest event"),
        """{"item":"event","executions":[1],"event":"SuiteStarting","threadName":"main","timeStamp":1}""" -> ("node", "a ScalaTest event with executions"),
        """{"item":"event","node":[],"executions":[1],"event":"SuiteStarting","threadName":"main","timeStamp":1}""" -> ("node", "a ScalaTest event with executions and an empty node"),
        s"""{"item":"event","node":$aSuite,"event":"SuiteStarting","threadName":"main","timeStamp":1}""" -> ("executions", "a ScalaTest event with a node"),
        s"""{"item":"event","node":$aSuite,"executions":[],"event":"SuiteStarting","threadName":"main","timeStamp":1}""" -> ("executions", "a ScalaTest event with a node and empty executions"),
        """{"item":"event","event":"DiscoveryStarting","timeStamp":1}""" -> ("threadName", "a DiscoveryStarting event"),
        """{"item":"event","event":"DiscoveryStarting","threadName":"main"}""" -> ("timeStamp", "a DiscoveryStarting event"),
        s"""{"item":"event","node":$aTest,"executions":[1,1],"event":"TestFailed","threadName":"main","timeStamp":1}""" -> ("message", "a TestFailed event"),
        s"""{"item":"event","node":$aTest,"executions":[1,1],"event":"MarkupProvided","threadName":"main","timeStamp":1}""" -> ("text", "a MarkupProvided event"),
        """{"item":"event","event":"RunStarting","configMap":{},"threadName":"main","timeStamp":1}""" -> ("testCount", "a RunStarting event"),
        """{"item":"event","event":"RunStarting","testCount":1,"threadName":"main","timeStamp":1}""" -> ("configMap", "a RunStarting event"),
        """{"item":"event","event":"RunCompleted","location":{"className":"c"},"threadName":"main","timeStamp":1}""" -> ("kind", "a location"),
        """{"item":"event","event":"RunCompleted","location":{"kind":"LineInFile","lineNumber":1},"threadName":"main","timeStamp":1}""" -> ("fileName", "a LineInFile location"),
        """{"item":"event","event":"RunCompleted","location":{"kind":"TopOfMethod","className":"c"},"threadName":"main","timeStamp":1}""" -> ("methodId", "a TopOfMethod location"),
        ("""{"item":"event","event":"RunCompleted","summary":{"testsSucceededCount":1,"testsFailedCount":2,"testsIgnoredCount":3,""" +
          """"testsPendingCount":4,"testsCanceledCount":5,"suitesCompletedCount":6,"suitesAbortedCount":7},""" +
          """"threadName":"main","timeStamp":1}""") -> ("scopesPendingCount", "a summary"),
        suiteAbortedLine("""{"className":"c"}""") -> ("id", "a throwable"),
        suiteAbortedLine("""{"id":1}""") -> ("className", "a throwable"),
        suiteAbortedLine("""{"id":1,"className":"c","stackTrace":[{"className":"c","methodName":"m"}]}""") -> ("text", "a stack frame")
      )
      linesAndMissingKeys.foreach { case (line, (key, description)) =>
        s"report the missing key $key of $description" in {
          whyUndecodable(line) shouldBe s"missing key $key"
        }
      }
    }

    "given a line holding a value the format does not give" should {
      "report an unknown item" in {
        whyUndecodable("""{"item":"progress","pid":1}""") shouldBe "unknown item progress"
      }
      "report an unknown event" in {
        whyUndecodable("""{"item":"event","event":"TestExploded","threadName":"main","timeStamp":1}""") shouldBe
          "unknown event TestExploded"
      }
      "report an unknown kind of node" in {
        whyUndecodable(s"""{"item":"structure","nodes":[{"id":$aSuite,"kind":"group","name":"g"}]}""") shouldBe
          "unknown kind group"
      }
      "report a place holding none of the keys of a suite's, a scope's or a test's place" in {
        whyUndecodable(
          """{"item":"structure","nodes":[{"id":[{"index":0}],"kind":"suite","name":"S","suiteId":"s","className":"c"}]}"""
        ) shouldBe "a place holds none of the keys suiteId, scope and test"
      }
      "report an unknown kind of location" in {
        whyUndecodable(
          """{"item":"event","event":"RunCompleted","location":{"kind":"Somewhere"},"threadName":"main","timeStamp":1}"""
        ) shouldBe "unknown kind Somewhere"
      }
      "report an unknown outcome" in {
        whyUndecodable("""{"item":"runEnded","outcome":"paused"}""") shouldBe "unknown outcome paused"
      }
      "report a test whose ignored is false, which the format never writes" in {
        whyUndecodable(
          s"""{"item":"structure","nodes":[{"id":$aTest,"kind":"test","parent":$aSuite,"position":0,"name":"t","label":"- t","ignored":false}]}"""
        ) shouldBe "ignored is not true"
      }
      "report a nested suite whose parent is not its id without the last place" in {
        whyUndecodable(
          s"""{"item":"structure","nodes":[{"id":[{"suiteId":"s","index":0},{"suiteId":"n","index":0}],"kind":"suite","parent":$aTest,"name":"N","suiteId":"n","className":"c"}]}"""
        ) shouldBe "parent is not the node's id without its last place"
      }
      "report a top-level suite that has a parent" in {
        whyUndecodable(
          s"""{"item":"structure","nodes":[{"id":$aSuite,"kind":"suite","parent":$aSuite,"name":"S","suiteId":"s","className":"c"}]}"""
        ) shouldBe "parent is not the node's id without its last place"
      }
      "report a scope whose parent is not its id without the last place" in {
        whyUndecodable(
          s"""{"item":"structure","nodes":[{"id":[{"suiteId":"s","index":0},{"scope":"t","index":0}],"kind":"scope","parent":$aTest,"position":0,"name":"t","label":"t"}]}"""
        ) shouldBe "parent is not the node's id without its last place"
      }
      "report a throwable whose propertyCheck is false, which the format never writes" in {
        whyUndecodable(suiteAbortedLine("""{"id":1,"className":"c","propertyCheck":false}""")) shouldBe
          "propertyCheck is not true"
      }
      "report a stack frame whose isNative is false, which the format never writes" in {
        whyUndecodable(
          suiteAbortedLine("""{"id":1,"className":"c","stackTrace":[{"className":"c","methodName":"m","isNative":false,"text":"c.m"}]}""")
        ) shouldBe "isNative is not true"
      }
    }

    "given a line it cannot decode" should {
      "throw an IllegalStateException whose message holds the reason and the line" in {
        val line = """{"item":"progress","pid":1}"""
        val thrown = the[IllegalStateException] thrownBy TestEventLogDecoder.decode(line)
        thrown.getMessage shouldBe s"unknown item progress, in $line"
      }
    }

    "given a line that is not one JSON object" should {
      val descriptionsLinesAndReasons = Seq(
        ("an empty line", "", "the text ends where a value should start, at offset 0"),
        ("a blank line", "  ", "the text ends where a value should start, at offset 2"),
        ("an array", "[]", "the line is not a JSON object"),
        ("a string", "\"runStarted\"", "the line is not a JSON object"),
        ("a number", "42", "the line is not a JSON object"),
        ("a boolean", "true", "the line is not a JSON object"),
        ("null", "null", "no value starts at offset 0"),
        ("two objects", runEndedLine + runEndedLine, "text follows the value at offset 41"),
        ("an object followed by other text", runEndedLine + " x", "text follows the value at offset 42"),
        ("an object that is not closed", """{"item":"runEnded","outcome":"completed"""", "neither ',' nor '}' is at offset 40"),
        ("an object with a trailing comma", """{"item":"runEnded","outcome":"completed",}""", "no key starts at offset 41"),
        ("an object with an unquoted key", """{item:"runEnded","outcome":"completed"}""", "no key starts at offset 1"),
        ("an object with a repeated key", """{"item":"runEnded","item":"runEnded","outcome":"completed"}""", "the key item is repeated"),
        ("an object with a null value", """{"item":"runEnded","outcome":null}""", "no value starts at offset 29"),
        ("a string with an unescaped control character", infoLine("some" + "\t" + "info"), "an unescaped control character is at offset 54"),
        ("a string with an unknown escape", infoLine("""some \x info"""), "an unknown escape is at offset 55"),
        ("a string with a short unicode escape", infoLine("""some \u12"""), "a \\u escape lacks four hexadecimal digits at offset 57"),
        (
          "a string with a unicode escape in fullwidth digits",
          infoLine("some \\u" + "\uFF10\uFF10\uFF16\uFF13 info"),
          "a \\u escape lacks four hexadecimal digits at offset 57"
        ),
        (
          "a number with a leading zero",
          """{"item":"runStarted","pid":01,"startTime":"1970-01-01T00:00:00Z"}""",
          "a number has a leading zero at offset 27"
        ),
        ("arrays nested 100000 deep and not closed", "[" * 100000, "the text ends where a value should start, at offset 100000"),
        ("arrays nested 100000 deep", "[" * 100000 + "]" * 100000, "the line is not a JSON object")
      )
      descriptionsLinesAndReasons.foreach { case (description, line, reason) =>
        s"report that $description cannot be decoded" in {
          whyUndecodable(line) shouldBe reason
        }
      }
    }

    "given a line holding a value of the wrong type" should {
      val descriptionsLinesAndReasons = Seq(
        ("an integer written as a string", """{"item":"runStarted","pid":"1","startTime":"1970-01-01T00:00:00Z"}""", "pid is not an integer"),
        (
          "a string written as a number",
          """{"item":"event","event":"DiscoveryStarting","threadName":7,"timeStamp":1}""",
          "threadName is not a string"
        ),
        ("nodes written as an object", """{"item":"structure","nodes":{}}""", "nodes is not an array of objects"),
        ("a node written as a string", """{"item":"structure","nodes":["suite"]}""", "nodes is not an array of objects"),
        (
          "an analysis line written as a number",
          s"""{"item":"event","node":$aTest,"executions":[1,1],"event":"TestFailed","message":"m","analysis":[1],"threadName":"main","timeStamp":1}""",
          "analysis is not an array of strings"
        ),
        (
          "a node's id written as a number",
          """{"item":"structure","nodes":[{"id":1,"kind":"suite","name":"S","suiteId":"s","className":"c"}]}""",
          "id is not an array of objects"
        ),
        (
          "a node's id written as null",
          """{"item":"structure","nodes":[{"id":null,"kind":"suite","name":"S","suiteId":"s","className":"c"}]}""",
          "no value starts at offset 35"
        ),
        (
          "a place written as a string",
          """{"item":"structure","nodes":[{"id":["s"],"kind":"suite","name":"S","suiteId":"s","className":"c"}]}""",
          "id is not an array of objects"
        ),
        (
          "a suite's place's suiteId written as a number",
          """{"item":"structure","nodes":[{"id":[{"suiteId":1,"index":0}],"kind":"suite","name":"S","suiteId":"s","className":"c"}]}""",
          "suiteId is not a string"
        ),
        (
          "a test's place's name written as null",
          s"""{"item":"structure","nodes":[{"id":[{"suiteId":"s","index":0},{"test":null}],"kind":"test","parent":$aSuite,"position":0,"name":"t","label":"- t"}]}""",
          "no value starts at offset 70"
        ),
        (
          "an event's node written as an object",
          """{"item":"event","node":{"suiteId":"s","index":0},"executions":[1],"event":"SuiteStarting","threadName":"main","timeStamp":1}""",
          "node is not an array of objects"
        ),
        (
          "an event's executions written as a number",
          s"""{"item":"event","node":$aSuite,"executions":1,"event":"SuiteStarting","threadName":"main","timeStamp":1}""",
          "executions is not an array of 32-bit integers from 1"
        ),
        (
          "an execution index written as a string",
          s"""{"item":"event","node":$aSuite,"executions":["1"],"event":"SuiteStarting","threadName":"main","timeStamp":1}""",
          "executions is not an array of 32-bit integers from 1"
        ),
        (
          "a config map value written as a number",
          """{"item":"event","event":"RunStarting","testCount":1,"configMap":{"key":1},"threadName":"main","timeStamp":1}""",
          "key is not a string"
        ),
        (
          "a property check mark written as a string",
          suiteAbortedLine("""{"id":1,"className":"c","propertyCheck":"true"}"""),
          "propertyCheck is not true"
        )
      )
      descriptionsLinesAndReasons.foreach { case (description, line, reason) =>
        s"report that $description cannot be decoded" in {
          whyUndecodable(line) shouldBe reason
        }
      }
    }

    "given a line holding a number that is not an integer its key can hold" should {
      val descriptionsLinesAndReasons = Seq(
        (
          "a pid written with a fraction",
          """{"item":"runStarted","pid":1.0,"startTime":"1970-01-01T00:00:00Z"}""",
          "the number at offset 27 is not an integer"
        ),
        (
          "a pid written with an exponent",
          """{"item":"runStarted","pid":1e3,"startTime":"1970-01-01T00:00:00Z"}""",
          "the number at offset 27 is not an integer"
        ),
        (
          "a pid above the greatest Long",
          """{"item":"runStarted","pid":9223372036854775808,"startTime":"1970-01-01T00:00:00Z"}""",
          "the integer at offset 27 does not fit a Long"
        ),
        (
          "a pid below the least Long",
          """{"item":"runStarted","pid":-9223372036854775809,"startTime":"1970-01-01T00:00:00Z"}""",
          "the integer at offset 27 does not fit a Long"
        ),
        (
          "a position above the greatest Int",
          s"""{"item":"structure","nodes":[{"id":$aTest,"kind":"test","parent":$aSuite,"position":2147483648,"name":"t","label":"- t"}]}""",
          "position is not a 32-bit integer from 0"
        ),
        (
          "a place's index above the greatest Int",
          """{"item":"structure","nodes":[{"id":[{"suiteId":"s","index":2147483648}],"kind":"suite","name":"S","suiteId":"s","className":"c"}]}""",
          "index is not a 32-bit integer from 0"
        ),
        (
          "an execution index above the greatest Int",
          s"""{"item":"event","node":$aSuite,"executions":[2147483648],"event":"SuiteStarting","threadName":"main","timeStamp":1}""",
          "executions is not an array of 32-bit integers from 1"
        ),
        (
          "a testCount above the greatest Int",
          """{"item":"event","event":"RunStarting","testCount":2147483648,"configMap":{},"threadName":"main","timeStamp":1}""",
          "testCount is not a 32-bit integer"
        ),
        (
          "a testCount below the least Int",
          """{"item":"event","event":"RunStarting","testCount":-2147483649,"configMap":{},"threadName":"main","timeStamp":1}""",
          "testCount is not a 32-bit integer"
        ),
        (
          "a location's lineNumber below the least Int",
          """{"item":"event","event":"RunCompleted","location":{"kind":"LineInFile","lineNumber":-2147483649,"fileName":"f"},""" +
            """"threadName":"main","timeStamp":1}""",
          "lineNumber is not a 32-bit integer"
        ),
        (
          "a summary's count above the greatest Int",
          """{"item":"event","event":"RunCompleted","summary":{"testsSucceededCount":1,"testsFailedCount":2,"testsIgnoredCount":3,""" +
            """"testsPendingCount":4,"testsCanceledCount":5,"suitesCompletedCount":6,"suitesAbortedCount":7,""" +
            """"scopesPendingCount":2147483648},"threadName":"main","timeStamp":1}""",
          "scopesPendingCount is not a 32-bit integer"
        ),
        (
          "a stackDepth's lineNumber above the greatest Int",
          suiteAbortedLine("""{"id":1,"className":"c","stackDepth":{"lineNumber":2147483648}}"""),
          "lineNumber is not a 32-bit integer"
        ),
        (
          "a stack frame's lineNumber above the greatest Int",
          suiteAbortedLine("""{"id":1,"className":"c","stackTrace":[{"className":"c","methodName":"m","lineNumber":2147483648,"text":"c.m"}]}"""),
          "lineNumber is not a 32-bit integer"
        )
      )
      descriptionsLinesAndReasons.foreach { case (description, line, reason) =>
        s"report that $description cannot be decoded" in {
          whyUndecodable(line) shouldBe reason
        }
      }
    }

    "given a line holding a ref to no earlier throwable, or an id two throwables share" should {
      val descriptionsLinesAndReasons = Seq(
        (
          "a ref to a throwable the event does not hold",
          suiteAbortedLine("""{"id":1,"className":"c","cause":{"ref":5}}"""),
          "ref 5 names no throwable the event holds before it"
        ),
        (
          "a ref to a throwable the event holds later",
          suiteAbortedLine("""{"id":1,"className":"c","cause":{"ref":2},"suppressed":[{"id":2,"className":"c"}]}"""),
          "ref 2 names no throwable the event holds before it"
        ),
        (
          "two throwables with one id",
          suiteAbortedLine("""{"id":1,"className":"c","cause":{"id":1,"className":"c"}}"""),
          "two throwables have id 1"
        )
      )
      descriptionsLinesAndReasons.foreach { case (description, line, reason) =>
        s"report that $description cannot be decoded" in {
          whyUndecodable(line) shouldBe reason
        }
      }
    }

    "given a line holding an integer below the least the format gives its key" should {
      val descriptionsLinesAndReasons = Seq(
        (
          "a test node's position below 0",
          s"""{"item":"structure","nodes":[{"id":$aTest,"kind":"test","parent":$aSuite,"position":-1,"name":"t","label":"- t"}]}""",
          "position is not a 32-bit integer from 0"
        ),
        (
          "a suite node's position below 0",
          s"""{"item":"structure","nodes":[{"id":[{"suiteId":"s","index":0},{"suiteId":"n","index":0}],"kind":"suite","parent":$aSuite,"position":-1,"name":"S","suiteId":"n","className":"c"}]}""",
          "position is not a 32-bit integer from 0"
        ),
        (
          "a suite's place's index below 0",
          """{"item":"structure","nodes":[{"id":[{"suiteId":"s","index":-1}],"kind":"suite","name":"S","suiteId":"s","className":"c"}]}""",
          "index is not a 32-bit integer from 0"
        ),
        (
          "a scope's place's index below 0",
          s"""{"item":"structure","nodes":[{"id":[{"suiteId":"s","index":0},{"scope":"t","index":-1}],"kind":"scope","parent":$aSuite,"position":0,"name":"t","label":"t"}]}""",
          "index is not a 32-bit integer from 0"
        ),
        (
          "an execution index below 1",
          s"""{"item":"event","node":$aTest,"executions":[1,0],"event":"TestStarting","threadName":"main","timeStamp":1}""",
          "executions is not an array of 32-bit integers from 1"
        ),
        ("a throwable's id below 1", suiteAbortedLine("""{"id":0,"className":"c"}"""), "id is not an integer from 1")
      )
      descriptionsLinesAndReasons.foreach { case (description, line, reason) =>
        s"report that $description cannot be decoded" in {
          whyUndecodable(line) shouldBe reason
        }
      }
    }

    "given text whose last line is torn" should {
      "yield the event of each whole line and no event from the torn line" in {
        val tornLine = throwableWithCauseAndSuppressedLine.take(throwableWithCauseAndSuppressedLine.length / 2)
        val text = runStartedLine + "\n" + structureLine + "\n" + tornLine
        text.split('\n').toVector.map(isDecodable) shouldBe Vector(true, true, false)
      }
      "yield no event from a line torn after any of its characters" in {
        val wholeLines = Seq(runStartedLine, structureLine, throwableWithCauseAndSuppressedLine, runEndedLine)
        val tornLines = wholeLines.flatMap(line => (0 until line.length).map(line.take))
        tornLines.filter(isDecodable) shouldBe Seq.empty
      }
    }
  }
}
