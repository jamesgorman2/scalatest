package org.scalatest.consolidation

import java.time.Instant
import org.scalatest.ScalaTestVersions
import org.scalatest.events.CapturedStream
import org.scalatest.events.DecodedEvent
import org.scalatest.events.JsonValue
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

    "given two runs" should {
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

  // A run of suite RowSuite, class samples.RowSuite, holding the structure lines, with each event one line of the event log.
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

  "A test of the JSON output" when {

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

  "A scope of the JSON output" when {

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
    }
  }

  "A suite of the JSON output" when {

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
}
