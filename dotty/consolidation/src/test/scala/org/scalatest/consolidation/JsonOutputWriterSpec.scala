package org.scalatest.consolidation

import java.time.Instant
import org.scalactic.source.Position
import org.scalatest.ScalaTestVersions
import org.scalatest.events.CapturedStream
import org.scalatest.events.DecodedEvent
import org.scalatest.events.DecodedStackDepth
import org.scalatest.events.DecodedStackFrame
import org.scalatest.events.DecodedThrowable
import org.scalatest.events.JsonValue
import org.scalatest.events.LineInFile
import org.scalatest.events.SeeStackDepthException
import org.scalatest.events.Summary
import org.scalatest.events.TestRunPart
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class JsonOutputWriterSpec extends AnyWordSpec with Matchers with ConsolidatedRuns with JsonOutputs {

  private val completedRun = TestRunPart.Events(
    """
    run started
    run: RunStarting
    run: RunCompleted
    run ended completed
    """
  )

  private def runNodeFedWith(events: DecodedEvent*): JsonValue = runNodeOf(feed => events.foreach(event => feed.add(runEvent(event))))

  private def runNodeOf(feedAfterRunStarted: RunFeed => Unit): JsonValue = {
    val json = new JsonSink
    val consolidation = startedWithoutMill(json)
    val feed = addRun(consolidation, TestRunPart.Events("run started"))
    feedAfterRunStarted(feed)
    feed.endEventStream()
    consolidation.end()
    json.runNode
  }

  private def endedWithOneRun(consolidation: Consolidation, endedAt: Option[Instant] = None): Unit = {
    addRun(consolidation, completedRun).endEventStream()
    consolidation.end(endedAt)
  }

  "A consolidation" when {

    "started with no JSON sink" should {
      "write the same human-readable output as one started with a JSON sink" in {
        val withoutJson = new OutputSink
        endedWithOneRun(Consolidation.start(withoutJson, coloured = true))
        val withJson = new OutputSink
        endedWithOneRun(Consolidation.start(withJson, coloured = true, jsonOutput = Some(new JsonSink)))
        withoutJson.lines shouldBe withJson.lines
      }
    }

    "started with a JSON sink" should {
      "write nothing to it before it ends" in {
        val json = new JsonSink
        val consolidation = startedWithoutMill(json)
        addRun(consolidation, completedRun).endEventStream()
        json.texts shouldBe empty
      }

      "write the JSON output to it once when it ends" in {
        val json = new JsonSink
        endedWithOneRun(startedWithoutMill(json))
        json.texts should have size 1
      }

      "give it the JSON output after writing the whole human-readable output" in {
        val humanReadable = new OutputSink
        var humanReadableLinesBeforeJson = Option.empty[Vector[String]]
        endedWithOneRun(Consolidation.start(humanReadable, coloured = false, jsonOutput = Some(_ => humanReadableLinesBeforeJson = Some(humanReadable.lines))))
        humanReadableLinesBeforeJson shouldBe Some(humanReadable.lines)
      }
    }
  }

  "The JSON output's root" when {

    "given a build tool, module selectors, both of its times, a failure and output" should {
      "hold them, with its outcome and duration, in the layout's key order" in {
        val json = new JsonSink
        val consolidation = Consolidation.start(
          new OutputSink,
          coloured = false,
          jsonOutput = Some(json),
          moduleSelectors = Vector(ModuleSelector("__.test", Vector(TestRunLabel("app.test"), moduleLabel)), moduleSelectedAsItself),
          buildTool = Some(BuildTool("Mill", "1.3.1")),
          startedAt = Some(Instant.ofEpochMilli(1000L))
        )
        consolidation.addRootOutput(CapturedLine.Stderr("discovering\n"))
        consolidation.addRootFailure("discovery failed")
        consolidation.end(Some(Instant.ofEpochMilli(1600L)))
        json.root shouldBe objectOf(
          "kind" -> text("root"),
          "scalaTestVersion" -> text(ScalaTestVersions.ScalaTestVersion),
          "buildTool" -> objectOf("name" -> text("Mill"), "version" -> text("1.3.1")),
          "moduleSelectors" -> arrayOf(
            objectOf("selector" -> text("__.test"), "modules" -> arrayOf(text("app.test"), text("core.test"))),
            objectOf("selector" -> text("core.test"), "modules" -> arrayOf(text("core.test")))
          ),
          "outcome" -> text("aborted"),
          "failures" -> arrayOf(objectOf("message" -> text("discovery failed"))),
          "startedAt" -> integer(1000L),
          "endedAt" -> integer(1600L),
          "duration" -> integer(600L),
          "contents" -> arrayOf(),
          "output" -> arrayOf(objectOf("kind" -> text("stdio"), "stream" -> text("stderr"), "text" -> text("discovering\n")))
        )
      }
    }

    "given only its start time" should {
      "have no duration" in {
        val json = new JsonSink
        Consolidation.start(new OutputSink, coloured = false, jsonOutput = Some(json), startedAt = Some(Instant.ofEpochMilli(1000L))).end()
        json.root.keys should contain("startedAt")
        json.root.keys should contain noneOf ("endedAt", "duration")
      }
    }

    "given no module selector" should {
      "hold an empty list of them" in {
        val json = new JsonSink
        Consolidation.start(new OutputSink, coloured = false, jsonOutput = Some(json)).end()
        json.root("moduleSelectors") shouldBe arrayOf()
      }
    }

    "given no failure and no output" should {
      "have no outcome, failures or output" in {
        val json = new JsonSink
        endedWithOneRun(startedWithoutMill(json))
        json.root.keys should contain noneOf ("outcome", "failures", "output")
      }
    }

    "given failures and output, interleaved" should {
      "hold each in the order it was attached" in {
        val json = new JsonSink
        val consolidation = startedWithoutMill(json)
        consolidation.addRootFailure("first failure")
        consolidation.addRootOutput(CapturedLine.Stderr("warn: slow discovery\n"))
        consolidation.addRootOutput(CapturedLine.Stdout("discovering tests\r\n"))
        consolidation.addRootFailure("second failure")
        consolidation.end()
        json.root("failures") shouldBe arrayOf(objectOf("message" -> text("first failure")), objectOf("message" -> text("second failure")))
        json.root("output") shouldBe arrayOf(
          objectOf("kind" -> text("stdio"), "stream" -> text("stderr"), "text" -> text("warn: slow discovery\n")),
          objectOf("kind" -> text("stdio"), "stream" -> text("stdout"), "text" -> text("discovering tests\r\n"))
        )
      }
    }

    "given runs of two modules" should {
      "hold the modules in ascending order by name" in {
        val json = new JsonSink
        val consolidation = startedWithoutMill(json)
        Vector("core.test", "app.test").foreach { module =>
          val feed = consolidation.addRun(TestRunLabel(module))
          feed.endEventStream()
        }
        consolidation.end()
        json.root("contents").items.map(_("name")) shouldBe Vector(text("app.test"), text("core.test"))
      }
    }
  }

  "A module of the JSON output" when {

    "timed with both of its times" should {
      "hold them and its duration, then its run nodes" in {
        val json = new JsonSink
        val consolidation = startedWithoutMill(json)
        consolidation.timeModule(moduleLabel, Some(Instant.ofEpochMilli(2000L)), Some(Instant.ofEpochMilli(2750L)))
        endedWithOneRun(consolidation)
        json.module.keys shouldBe Vector("kind", "name", "startedAt", "endedAt", "duration", "contents")
        json.module("kind") shouldBe text("module")
        json.module("name") shouldBe text("core.test")
        json.module("startedAt") shouldBe integer(2000L)
        json.module("endedAt") shouldBe integer(2750L)
        json.module("duration") shouldBe integer(750L)
      }
    }

    "timed with only its end time" should {
      "have no duration" in {
        val json = new JsonSink
        val consolidation = startedWithoutMill(json)
        consolidation.timeModule(moduleLabel, None, Some(Instant.ofEpochMilli(2750L)))
        endedWithOneRun(consolidation)
        json.module.keys shouldBe Vector("kind", "name", "endedAt", "contents")
      }
    }

    "timed with no run" should {
      "be written with no run node" in {
        val json = new JsonSink
        val consolidation = startedWithoutMill(json)
        consolidation.timeModule(moduleLabel, Some(Instant.ofEpochMilli(2000L)), Some(Instant.ofEpochMilli(2750L)))
        consolidation.end()
        json.module("contents") shouldBe arrayOf()
      }
    }

    "given two runs with no fork group" should {
      "hold their run nodes numbered from 1 in the order they were added" in {
        val json = new JsonSink
        val consolidation = startedWithoutMill(json)
        addRun(consolidation, completedRun).endEventStream()
        addRun(consolidation, completedRun).endEventStream()
        consolidation.end()
        json.module("contents").items.map(_("number")) shouldBe Vector(integer(1L), integer(2L))
      }
    }
  }

  "A run node of the JSON output" when {

    "its run reported every run-level event and a captured line" should {
      "hold each value in the layout's key order" in {
        val summary = Summary(1, 2, 3, 4, 5, 6, 7, 8)
        val runNode = runNodeOf { feed =>
          Vector(
            DecodedEvent.RunStarting(3, Map("zone" -> "UTC", "greeting" -> "hello"), None, "main", 100L),
            DecodedEvent.DiscoveryStarting("main", 110L),
            DecodedEvent.DiscoveryCompleted(Some(15L), "main", 125L),
            DecodedEvent.RunAborted("boom", None, Some(400L), Some(summary), None, "main", 500L)
          ).foreach(event => feed.add(runEvent(event)))
          feed.add(CapturedLine.Stdout("dividing\n"))
          feed.add("run ended aborted".toRunEvent)
        }
        runNode shouldBe objectOf(
          "kind" -> text("run"),
          "number" -> integer(1L),
          "testCount" -> integer(3L),
          "configMap" -> objectOf("greeting" -> text("hello"), "zone" -> text("UTC")),
          "discovery" -> objectOf("startedAt" -> integer(110L), "duration" -> integer(15L)),
          "outcome" -> text("aborted"),
          "message" -> text("boom"),
          "startedAt" -> integer(100L),
          "endedAt" -> integer(500L),
          "duration" -> integer(400L),
          "summary" -> objectOf(
            "testsSucceededCount" -> integer(1L),
            "testsFailedCount" -> integer(2L),
            "testsIgnoredCount" -> integer(3L),
            "testsPendingCount" -> integer(4L),
            "testsCanceledCount" -> integer(5L),
            "suitesCompletedCount" -> integer(6L),
            "suitesAbortedCount" -> integer(7L),
            "scopesPendingCount" -> integer(8L)
          ),
          "contents" -> arrayOf(),
          "output" -> arrayOf(objectOf("kind" -> text("stdio"), "stream" -> text("stdout"), "text" -> text("dividing\n")))
        )
      }
    }

    "its RunStarting expects no tests" should {
      "have no test count" in {
        runNodeFedWith(DecodedEvent.RunStarting(0, Map.empty, None, "main", 100L)).keys should not contain "testCount"
      }
    }

    "its run reported no run-level event" should {
      "hold only its kind, number and contents" in {
        runNodeFedWith() shouldBe objectOf("kind" -> text("run"), "number" -> integer(1L), "contents" -> arrayOf())
      }
    }

    "its run reported discovery starting but not completing" should {
      "have no discovery" in {
        runNodeFedWith(DecodedEvent.DiscoveryStarting("main", 110L)).keys should not contain "discovery"
      }
    }

    "its run reported a RunCompleted and then a RunAborted" should {
      "take its end time from the first" in {
        val runNode = runNodeFedWith(
          DecodedEvent.RunCompleted(Some(10L), None, None, "main", 200L),
          DecodedEvent.RunAborted("late", None, Some(20L), None, None, "main", 300L)
        )
        runNode("endedAt") shouldBe integer(200L)
        runNode("duration") shouldBe integer(10L)
      }
    }

    "its run ended aborted with no RunAborted" should {
      "be aborted with no message" in {
        val json = jsonOutputOfRun(
          TestRunPart.Events(
            """
            run started
            run: RunStarting
            run ended aborted
            """
          )
        )
        json.runNode("outcome") shouldBe text("aborted")
        json.runNode.keys should not contain "message"
      }
    }

    "given lines captured from its processes" should {
      "hold them in the order they were fed, each with its terminator" in {
        val json = jsonOutputOfRun(
          TestRunPart.CapturedLine(CapturedStream.Stdout, "before\n"),
          TestRunPart.Events(
            """
            run started
            run: RunStarting
            """
          ),
          TestRunPart.CapturedLine(CapturedStream.Stderr, "after\r\n"),
          TestRunPart.CapturedLine(CapturedStream.Stdout, "unterminated"),
          TestRunPart.Events("run ended completed")
        )
        json.runNode("output") shouldBe arrayOf(
          objectOf("kind" -> text("stdio"), "stream" -> text("stdout"), "text" -> text("before\n")),
          objectOf("kind" -> text("stdio"), "stream" -> text("stderr"), "text" -> text("after\r\n")),
          objectOf("kind" -> text("stdio"), "stream" -> text("stdout"), "text" -> text("unterminated"))
        )
      }
    }

    "given no captured line" should {
      "have no output" in {
        jsonOutputOfRun(completedRun).runNode.keys should not contain "output"
      }
    }
  }

  private def jsonOutputOfRowSuite(structure: Seq[String], events: String*): JsonSink =
    jsonOutputOfRun(
      TestRunPart.Events(
        s"""
        run started
        run: RunStarting
        structure
          suite "RowSuite", class "samples.RowSuite"
            ${structure.mkString("\n            ")}
        ${events.mkString("\n        ")}
        run: RunCompleted
        run ended completed
        """
      )
    )

  private val testAdds = Seq("""test "adds", label "- adds"""")

  private val testsAddsAndSubtracts = Seq("""test "adds", label "- adds"""", """test "subtracts", label "- subtracts"""")

  "A test node in the JSON output" when {

    "it started and succeeded" should {
      "hold each value in the layout's key order" in {
        val json = jsonOutputOfRowSuite(
          testAdds,
          """suite "RowSuite": SuiteStarting""",
          """test "adds": TestStarting(location: LineInFile(7, "RowSuite.scala", Some("/build/RowSuite.scala")), threadName: "pool-1", timeStamp: 100)""",
          """test "adds": TestSucceeded(duration: 8, threadName: "pool-2", timeStamp: 110)""",
          """suite "RowSuite": SuiteCompleted"""
        )
        json.node("test", "adds") shouldBe objectOf(
          "kind" -> text("test"),
          "name" -> text("adds"),
          "label" -> text("- adds"),
          "location" -> objectOf("filePath" -> text("/build/RowSuite.scala"), "lineNumber" -> integer(7L)),
          "outcome" -> text("succeeded"),
          "startedAt" -> integer(100L),
          "endedAt" -> integer(110L),
          "duration" -> integer(8L),
          "threadName" -> text("pool-1"),
          "contents" -> arrayOf()
        )
      }
    }

    "it failed" should {
      "hold its outcome and then its message, each value in the layout's key order" in {
        val json = jsonOutputOfRowSuite(
          testAdds,
          """suite "RowSuite": SuiteStarting""",
          """test "adds": TestStarting(location: LineInFile(7, "RowSuite.scala", None), threadName: "pool-1", timeStamp: 100)""",
          """test "adds": TestFailed(message: "2 did not equal 3", duration: 8, timeStamp: 110)""",
          """suite "RowSuite": SuiteCompleted"""
        )
        json.node("test", "adds").keys shouldBe
          Vector("kind", "name", "label", "location", "outcome", "message", "startedAt", "endedAt", "duration", "threadName", "contents")
      }
    }

    "it never started in a suite that aborted" should {
      "hold its outcome and then its reason, each value in the layout's key order" in {
        val json = jsonOutputOfRowSuite(
          testAdds,
          """suite "RowSuite": SuiteStarting""",
          """suite "RowSuite": SuiteAborted(message: "no fixture")"""
        )
        json.node("test", "adds").keys shouldBe Vector("kind", "name", "label", "outcome", "reason", "contents")
      }
    }

    "its closing event carries no duration" should {
      "have no duration, though it has both times" in {
        val json = jsonOutputOfRowSuite(
          testAdds,
          """suite "RowSuite": SuiteStarting""",
          """test "adds": TestStarting(timeStamp: 100)""",
          """test "adds": TestSucceeded(timeStamp: 110)""",
          """suite "RowSuite": SuiteCompleted"""
        )
        json.node("test", "adds").keys should contain allOf ("startedAt", "endedAt")
        json.node("test", "adds").keys should not contain "duration"
      }
    }

    "its TestStarting's location is SeeStackDepthException" should {
      "take its location from its closing event" in {
        val json = jsonOutputOfRowSuite(
          testAdds,
          """suite "RowSuite": SuiteStarting""",
          """test "adds": TestStarting(location: SeeStackDepthException)""",
          """test "adds": TestFailed(location: TopOfMethod("samples.RowSuite", "def adds(): Unit"))""",
          """suite "RowSuite": SuiteCompleted"""
        )
        json.node("test", "adds")("location") shouldBe objectOf("className" -> text("samples.RowSuite"), "methodId" -> text("def adds(): Unit"))
      }
    }

    "neither its opening nor its closing event gives a location other than SeeStackDepthException" should {
      "have no location" in {
        val json = jsonOutputOfRowSuite(
          testAdds,
          """suite "RowSuite": SuiteStarting""",
          """test "adds": TestStarting""",
          """test "adds": TestFailed(location: SeeStackDepthException)""",
          """suite "RowSuite": SuiteCompleted"""
        )
        json.node("test", "adds").keys should not contain "location"
      }
    }

    "its location is a line in a file whose path is not known" should {
      "give the file's name" in {
        val json = jsonOutputOfRowSuite(
          testAdds,
          """suite "RowSuite": SuiteStarting""",
          """test "adds": TestStarting(location: LineInFile(7, "RowSuite.scala", None))""",
          """test "adds": TestSucceeded""",
          """suite "RowSuite": SuiteCompleted"""
        )
        json.node("test", "adds")("location") shouldBe objectOf("fileName" -> text("RowSuite.scala"), "lineNumber" -> integer(7L))
      }
    }

    "its location is a line in a file compiled without file pathnames filled" should {
      "give the file's name" in {
        val json = jsonOutputOfRowSuite(
          testAdds,
          """suite "RowSuite": SuiteStarting""",
          s"""test "adds": TestStarting(location: LineInFile(7, "RowSuite.scala", Some("${Position.unfilledFilePathname}")))""",
          """test "adds": TestSucceeded""",
          """suite "RowSuite": SuiteCompleted"""
        )
        json.node("test", "adds")("location") shouldBe objectOf("fileName" -> text("RowSuite.scala"), "lineNumber" -> integer(7L))
      }
    }

    "it is canceled" should {
      "hold the outcome canceled and its TestCanceled's message" in {
        val json = jsonOutputOfRowSuite(
          testAdds,
          """suite "RowSuite": SuiteStarting""",
          """test "adds": TestStarting""",
          """test "adds": TestCanceled(message: "no database")""",
          """suite "RowSuite": SuiteCompleted"""
        )
        json.node("test", "adds")("outcome") shouldBe text("canceled")
        json.node("test", "adds")("message") shouldBe text("no database")
      }
    }

    "it is open when its run aborts" should {
      "be aborted, ended at the RunAborted's time, as its open suite is" in {
        val json = jsonOutputOfRun(
          TestRunPart.Events(
            """
            run started
            run: RunStarting
            structure
              suite "RowSuite", class "samples.RowSuite"
                test "adds", label "- adds"
            suite "RowSuite": SuiteStarting(timeStamp: 90)
            test "adds": TestStarting(timeStamp: 100)
            run: RunAborted(message: "out of memory", timeStamp: 175)
            run ended aborted
            """
          )
        )
        val adds = json.node("test", "adds")
        adds("outcome") shouldBe text("aborted")
        adds("endedAt") shouldBe integer(175L)
        adds("duration") shouldBe integer(75L)
        adds.keys should not contain "message"
        json.node("suite", "RowSuite")("endedAt") shouldBe integer(175L)
      }
    }
  }

  "A scope node in the JSON output" when {

    "it is pending" should {
      "take its end time from its ScopePending and its duration from its times" in {
        val json = jsonOutputOfRowSuite(
          Seq("""scope "A Queue", label "A Queue""""),
          """suite "RowSuite": SuiteStarting""",
          """scope "A Queue": ScopeOpened(timeStamp: 100)""",
          """scope "A Queue": ScopePending(timeStamp: 104)""",
          """suite "RowSuite": SuiteCompleted"""
        )
        val scope = json.node("scope", "A Queue")
        scope("endedAt") shouldBe integer(104L)
        scope("duration") shouldBe integer(4L)
      }

      "hold each value in the layout's key order" in {
        val json = jsonOutputOfRowSuite(
          Seq("""scope "A Queue", label "A Queue""""),
          """suite "RowSuite": SuiteStarting""",
          """scope "A Queue": ScopeOpened(location: LineInFile(6, "RowSuite.scala", None), threadName: "pool-1", timeStamp: 100)""",
          """scope "A Queue": ScopePending(timeStamp: 104)""",
          """suite "RowSuite": SuiteCompleted"""
        )
        json.node("scope", "A Queue").keys shouldBe Vector("kind", "name", "label", "location", "outcome", "startedAt", "endedAt", "duration", "contents")
      }
    }

    "its ScopeOpened gives no location and its ScopeClosed gives one" should {
      "have no location" in {
        val json = jsonOutputOfRowSuite(
          Seq("""scope "A Queue", label "A Queue""""),
          """suite "RowSuite": SuiteStarting""",
          """scope "A Queue": ScopeOpened""",
          """scope "A Queue": ScopeClosed(location: LineInFile(6, "RowSuite.scala", None))""",
          """suite "RowSuite": SuiteCompleted"""
        )
        json.node("scope", "A Queue").keys should not contain "location"
      }
    }
  }

  "A suite node in the JSON output" when {

    "it completed, and its tests' locations share one file" should {
      "hold each value in the layout's key order, with that file in its location" in {
        val json = jsonOutputOfRowSuite(
          testsAddsAndSubtracts,
          """suite "RowSuite": SuiteStarting(location: TopOfClass("samples.RowSuite"), threadName: "pool-1", timeStamp: 100)""",
          """test "adds": TestStarting(location: LineInFile(5, "RowSuite.scala", Some("/build/RowSuite.scala")))""",
          """test "adds": TestSucceeded""",
          """test "subtracts": TestStarting(location: LineInFile(9, "RowSuite.scala", Some("/build/RowSuite.scala")))""",
          """test "subtracts": TestSucceeded""",
          """suite "RowSuite": SuiteCompleted(duration: 30, threadName: "pool-2", timeStamp: 140)"""
        )
        val suite = json.node("suite", "RowSuite")
        suite.keys shouldBe Vector("kind", "name", "suiteId", "location", "startedAt", "endedAt", "duration", "threadName", "contents")
        suite("kind") shouldBe text("suite")
        suite("suiteId") shouldBe text("samples.RowSuite")
        suite("location") shouldBe objectOf("className" -> text("samples.RowSuite"), "filePath" -> text("/build/RowSuite.scala"))
        suite("startedAt") shouldBe integer(100L)
        suite("endedAt") shouldBe integer(140L)
        suite("duration") shouldBe integer(30L)
        suite("threadName") shouldBe text("pool-1")
      }
    }

    "it aborted" should {
      "hold its outcome and then its message, each value in the layout's key order" in {
        val json = jsonOutputOfRowSuite(
          testAdds,
          """suite "RowSuite": SuiteStarting(threadName: "pool-1", timeStamp: 100)""",
          """suite "RowSuite": SuiteAborted(message: "no fixture", duration: 5, timeStamp: 105)"""
        )
        json.node("suite", "RowSuite").keys shouldBe
          Vector("kind", "name", "suiteId", "location", "outcome", "message", "startedAt", "endedAt", "duration", "threadName", "contents")
      }
    }

    "it completed and its SuiteCompleted carries no duration" should {
      "have no duration, though it has both times and its tests have durations" in {
        val json = jsonOutputOfRowSuite(
          testsAddsAndSubtracts,
          """suite "RowSuite": SuiteStarting(timeStamp: 100)""",
          """test "adds": TestStarting(timeStamp: 105)""",
          """test "adds": TestSucceeded(duration: 8, timeStamp: 113)""",
          """test "subtracts": TestStarting(timeStamp: 120)""",
          """test "subtracts": TestSucceeded(duration: 9, timeStamp: 129)""",
          """suite "RowSuite": SuiteCompleted(timeStamp: 140)"""
        )
        val suite = json.node("suite", "RowSuite")
        suite("contents").items.map(_("duration")) shouldBe Vector(integer(8L), integer(9L))
        suite.keys should contain allOf ("startedAt", "endedAt")
        suite.keys should not contain "duration"
      }
    }

    "its tests' locations name different files" should {
      "give only its class name as its location" in {
        val json = jsonOutputOfRowSuite(
          testsAddsAndSubtracts,
          """suite "RowSuite": SuiteStarting""",
          """test "adds": TestStarting(location: LineInFile(5, "RowSuite.scala", None))""",
          """test "adds": TestSucceeded""",
          """test "subtracts": TestStarting(location: LineInFile(9, "Helpers.scala", None))""",
          """test "subtracts": TestSucceeded""",
          """suite "RowSuite": SuiteCompleted"""
        )
        json.node("suite", "RowSuite")("location") shouldBe objectOf("className" -> text("samples.RowSuite"))
      }
    }

    "it never started" should {
      "be aborted with reason notRun, with no times or thread" in {
        val suite = jsonOutputOfRowSuite(testAdds).node("suite", "RowSuite")
        suite("outcome") shouldBe text("aborted")
        suite("reason") shouldBe text("notRun")
        suite.keys should contain noneOf ("startedAt", "endedAt", "duration", "threadName")
      }
    }

    "it started and its run completed with neither its SuiteCompleted nor a RunAborted" should {
      "be aborted with no end time, duration or message" in {
        val suite = jsonOutputOfRowSuite(testAdds, """suite "RowSuite": SuiteStarting(timeStamp: 100)""").node("suite", "RowSuite")
        suite("outcome") shouldBe text("aborted")
        suite.keys should contain noneOf ("endedAt", "duration", "message")
      }
    }
  }

  "A run node's top-level suites" when {

    "two of one name started and a third of that name never started" should {
      "hold those that started in the order they started, then the one that never started" in {
        val json = jsonOutputOfRun(
          TestRunPart.Events(
            """
            run started
            run: RunStarting
            structure
              suite "Same", class "samples.First"
            structure
              suite "Same", class "samples.Second"
            structure
              suite "Same", class "samples.Third"
            suite "Same" #3: SuiteStarting
            suite "Same" #3: SuiteCompleted
            suite "Same" #1: SuiteStarting
            suite "Same" #1: SuiteCompleted
            run: RunCompleted
            run ended completed
            """
          )
        )
        json.runNode("contents").items.map(_("suiteId")) shouldBe Vector(text("samples.Third"), text("samples.First"), text("samples.Second"))
      }
    }
  }

  private def throwable(className: String, message: Option[String] = None, frames: Vector[DecodedStackFrame] = Vector.empty): DecodedThrowable =
    new DecodedThrowable(className, message, None, false, frames)

  private def linked(throwable: DecodedThrowable, cause: Option[DecodedThrowable] = None, suppressed: Vector[DecodedThrowable] = Vector.empty): DecodedThrowable = {
    throwable.link(cause, suppressed)
    throwable
  }

  private def stackDepthException(stackDepth: DecodedStackDepth): DecodedThrowable =
    new DecodedThrowable("org.scalatest.exceptions.TestFailedException", Some("failed"), Some(stackDepth), false, Vector.empty)

  private def runAbortedWith(thrown: DecodedThrowable): DecodedEvent.RunAborted =
    DecodedEvent.RunAborted("boom", Some(thrown), None, None, None, "main", 500L)

  private def recordOf(thrown: DecodedThrowable): JsonValue = runNodeFedWith(runAbortedWith(thrown))("throwable")

  "A message in the JSON output" when {

    "it carries a location and a throwable" should {
      "hold each value in the layout's key order" in {
        val info = DecodedEvent.InfoProvided("checked", Some(throwable("java.lang.IllegalStateException")), Some(LineInFile(9, "RowSuite.scala", None)), "pool-1", 120L)
        runNodeFedWith(info)("output").only shouldBe objectOf(
          "kind" -> text("info"),
          "message" -> text("checked"),
          "at" -> integer(120L),
          "location" -> objectOf("fileName" -> text("RowSuite.scala"), "lineNumber" -> integer(9L)),
          "throwable" -> objectOf("id" -> integer(1L), "className" -> text("java.lang.IllegalStateException"), "stackTrace" -> arrayOf()),
          "threadName" -> text("pool-1")
        )
      }
    }

    "its message has leading and trailing white space" should {
      "hold it as sent" in {
        runNodeFedWith(DecodedEvent.NoteProvided("  spaced  ", None, None, "main", 120L))("output").only("message") shouldBe text("  spaced  ")
      }
    }

    "its location is SeeStackDepthException" should {
      "have no location" in {
        runNodeFedWith(DecodedEvent.AlertProvided("look", None, Some(SeeStackDepthException), "main", 120L))("output").only.keys should not contain "location"
      }
    }

    "it is sent in a scope" should {
      "hold its thread's name, since a scope has none" in {
        val json = jsonOutputOfRowSuite(
          Seq("""scope "A Stack", label "A Stack""""),
          """suite "RowSuite": SuiteStarting(threadName: "main")""",
          """scope "A Stack": ScopeOpened(threadName: "main")""",
          """scope "A Stack": InfoProvided(message: "in scope", threadName: "main")""",
          """scope "A Stack": ScopeClosed(threadName: "main")""",
          """suite "RowSuite": SuiteCompleted"""
        )
        json.node("scope", "A Stack")("output").only("threadName") shouldBe text("main")
      }
    }

    "it is sent in a suite on the suite's thread" should {
      "have no thread name" in {
        val json = jsonOutputOfRowSuite(
          Seq.empty,
          """suite "RowSuite": SuiteStarting(threadName: "main")""",
          """suite "RowSuite": InfoProvided(message: "in suite", threadName: "main")""",
          """suite "RowSuite": SuiteCompleted"""
        )
        json.node("suite", "RowSuite")("output").only.keys should not contain "threadName"
      }
    }
  }

  "A test node with messages or a throwable in the JSON output" when {

    "it has messages" should {
      "hold them as its output after its contents" in {
        val json = jsonOutputOfRowSuite(
          testAdds,
          """suite "RowSuite": SuiteStarting""",
          """test "adds": TestStarting""",
          """test "adds": InfoProvided(message: "adding")""",
          """test "adds": TestSucceeded""",
          """suite "RowSuite": SuiteCompleted"""
        )
        json.node("test", "adds").keys.takeRight(2) shouldBe Vector("contents", "output")
      }
    }

    "it has no message" should {
      "have no output" in {
        val json = jsonOutputOfRowSuite(testAdds, """suite "RowSuite": SuiteStarting""", """test "adds": TestStarting""", """test "adds": TestSucceeded""", """suite "RowSuite": SuiteCompleted""")
        json.node("test", "adds").keys should not contain "output"
      }
    }

    "it failed with a throwable" should {
      "hold the throwable after its message" in {
        val json = jsonOutputOfRowSuite(
          testAdds,
          """suite "RowSuite": SuiteStarting""",
          """test "adds": TestStarting""",
          """test "adds": TestFailed(message: "failed", throwable: java.lang.IllegalStateException "failed")""",
          """suite "RowSuite": SuiteCompleted"""
        )
        json.node("test", "adds").keys.dropWhile(_ != "message").take(2) shouldBe Vector("message", "throwable")
      }
    }

    "it is a test aborted by its DistributedTestRunnerSuite" should {
      "hold that SuiteAborted's throwable" in {
        val json = jsonOutputOfRowSuite(
          testAdds,
          """suite "RowSuite": SuiteStarting""",
          """test "adds": TestStarting""",
          """test "adds": TestSucceeded""",
          """test "adds": SuiteAborted(message: "no teardown", throwable: java.lang.IllegalStateException "no teardown")""",
          """suite "RowSuite": SuiteCompleted"""
        )
        json.node("test", "adds")("throwable")("message") shouldBe text("no teardown")
      }
    }
  }

  "A run node of a run aborted with a throwable" should {
    "hold the throwable after its message" in {
      val runNode = runNodeFedWith(runAbortedWith(throwable("java.lang.IllegalStateException", Some("boom"))))
      runNode.keys.dropWhile(_ != "message").take(2) shouldBe Vector("message", "throwable")
      runNode("throwable") shouldBe objectOf("id" -> integer(1L), "className" -> text("java.lang.IllegalStateException"), "message" -> text("boom"), "stackTrace" -> arrayOf())
    }
  }

  "A throwable record in the JSON output" when {

    "it is a StackDepth exception whose failed code's path is known" should {
      "give the path and line number as its location, with no stack trace" in {
        recordOf(stackDepthException(DecodedStackDepth(Some("RowSuite.scala"), Some("/build/RowSuite.scala"), Some(7)))) shouldBe objectOf(
          "id" -> integer(1L),
          "className" -> text("org.scalatest.exceptions.TestFailedException"),
          "message" -> text("failed"),
          "location" -> objectOf("filePath" -> text("/build/RowSuite.scala"), "lineNumber" -> integer(7L))
        )
      }
    }

    "it is a StackDepth exception whose failed code's path is not known" should {
      "give the file's name as its location" in {
        recordOf(stackDepthException(DecodedStackDepth(Some("RowSuite.scala"), None, Some(7))))("location") shouldBe
          objectOf("fileName" -> text("RowSuite.scala"), "lineNumber" -> integer(7L))
      }
    }

    "it is a StackDepth exception whose failed code was compiled without file pathnames filled" should {
      "give the file's name as its location" in {
        recordOf(stackDepthException(DecodedStackDepth(Some("RowSuite.scala"), Some(Position.unfilledFilePathname), Some(7))))("location") shouldBe
          objectOf("fileName" -> text("RowSuite.scala"), "lineNumber" -> integer(7L))
      }
    }

    "it is a StackDepth exception whose failed code is not known" should {
      "have neither a location nor a stack trace" in {
        recordOf(stackDepthException(DecodedStackDepth(None, None, None))).keys shouldBe Vector("id", "className", "message")
      }
    }

    "its frames have unknown files, unknown or negative line numbers and native methods" should {
      "omit each unknown value, and mark only a native frame" in {
        val frames = Vector(
          DecodedStackFrame("samples.Row", "add", Some("Row.scala"), Some(3), false, "samples.Row.add(Row.scala:3)"),
          DecodedStackFrame("samples.Row", "load", Some("Row.scala"), Some(-1), false, "samples.Row.load(Row.scala)"),
          DecodedStackFrame("samples.Row", "find", None, None, false, "samples.Row.find(Unknown Source)"),
          DecodedStackFrame("java.lang.Thread", "sleep", None, Some(-2), true, "java.lang.Thread.sleep(Native Method)")
        )
        recordOf(throwable("java.lang.IllegalStateException", frames = frames))("stackTrace") shouldBe arrayOf(
          objectOf("className" -> text("samples.Row"), "methodName" -> text("add"), "fileName" -> text("Row.scala"), "lineNumber" -> integer(3L)),
          objectOf("className" -> text("samples.Row"), "methodName" -> text("load"), "fileName" -> text("Row.scala")),
          objectOf("className" -> text("samples.Row"), "methodName" -> text("find")),
          objectOf("className" -> text("java.lang.Thread"), "methodName" -> text("sleep"), "isNative" -> JsonValue.Bool(true))
        )
      }
    }

    "its cause has a cause, and it has two suppressed throwables" should {
      "number them depth-first, the whole cause chain before the suppressed throwables" in {
        val record = recordOf(
          linked(
            throwable("Outer"),
            cause = Some(linked(throwable("Cause"), cause = Some(throwable("RootCause")))),
            suppressed = Vector(throwable("FirstSuppressed"), throwable("SecondSuppressed"))
          )
        )
        record("id") shouldBe integer(1L)
        record("cause")("id") shouldBe integer(2L)
        record("cause")("cause")("id") shouldBe integer(3L)
        record("suppressed").items.map(_("id")) shouldBe Vector(integer(4L), integer(5L))
      }
    }

    "one throwable is both its cause and its suppressed throwable" should {
      "record it as the cause and refer to it as the suppressed throwable" in {
        val shared = throwable("Shared")
        val record = recordOf(linked(throwable("Outer"), cause = Some(shared), suppressed = Vector(shared)))
        record("cause")("id") shouldBe integer(2L)
        record("suppressed") shouldBe arrayOf(objectOf("ref" -> integer(2L)))
      }
    }

    "two throwables are alike but not the same object" should {
      "record each in full" in {
        val record = recordOf(linked(throwable("Outer"), cause = Some(throwable("Same", Some("same"))), suppressed = Vector(throwable("Same", Some("same")))))
        record("suppressed").only("id") shouldBe integer(3L)
      }
    }

    "it is its own cause" should {
      "refer to itself as its cause" in {
        val itself = throwable("Itself")
        recordOf(linked(itself, cause = Some(itself)))("cause") shouldBe objectOf("ref" -> integer(1L))
      }
    }

    "its cause chain is too long to recurse over" should {
      "record every throwable of the chain" in {
        val chainLength = 100000
        val outermost = (1 until chainLength).foldLeft(throwable("Innermost"))((cause, _) => linked(throwable("Outer"), cause = Some(cause)))
        val ids = Iterator.iterate(Option(recordOf(outermost)))(_.flatMap(_.get("cause"))).takeWhile(_.nonEmpty).flatten.map(_("id")).toVector
        ids.size shouldBe chainLength
        ids.last shouldBe integer(chainLength.toLong)
      }
    }

    "the same throwable is carried by two messages" should {
      "number each message's record from 1, referring to nothing in the other" in {
        val shared = throwable("Shared")
        val output = runNodeFedWith(
          DecodedEvent.InfoProvided("first", Some(shared), None, "main", 110L),
          DecodedEvent.InfoProvided("second", Some(shared), None, "main", 120L)
        )("output")
        output.items.map(_("throwable")("id")) shouldBe Vector(integer(1L), integer(1L))
      }
    }
  }
}
