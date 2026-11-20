package org.scalatest.consolidation

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.Properties
import org.scalatest.GivenWhenThen
import org.scalatest.events.DecodedEvent
import org.scalatest.events.DecodedStackFrame
import org.scalatest.events.DecodedThrowable
import org.scalatest.events.JsonValue
import org.scalatest.events.Summary
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

final class MillPluginDocumentationSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with ConsolidatedRuns with JsonOutputs {

  private val millPluginDirectory: Path = {
    val properties = new Properties
    val resource = getClass.getResourceAsStream("/repository.properties")
    try properties.load(resource) finally resource.close()
    Paths.get(properties.getProperty("millPlugin"))
  }

  private def millPluginDocument(name: String): String = Files.readString(millPluginDirectory.resolve(name), StandardCharsets.UTF_8)

  private def keysDefinedIn(document: String): Set[String] = {
    val bulletLeads = """(?m)^\s*- ((?:`\w+`(?:, | or | and )?)+)(?: — |\.$)""".r
    val objectShapes = """\{[^{}]*\}""".r
    val listsAfterColon = """: ((?:`\w+`, )+`\w+`)\.""".r
    val backticked = """`(\w+)`""".r
    val quoted = """"(\w+)"""".r
    val fromBullets = bulletLeads.findAllMatchIn(document).flatMap(lead => backticked.findAllMatchIn(lead.group(1)))
    val fromLists = listsAfterColon.findAllMatchIn(document).flatMap(list => backticked.findAllMatchIn(list.group(1)))
    val fromShapes = objectShapes.findAllMatchIn(document).flatMap(shape => quoted.findAllMatchIn(shape.matched))
    (fromBullets ++ fromLists ++ fromShapes).map(_.group(1)).toSet
  }

  private def keysOf(value: JsonValue): Set[String] =
    value match {
      // A config map's keys are the run's own, not the format's.
      case JsonValue.Object(fields) => fields.flatMap((key, field) => if (key == "configMap") Set(key) else keysOf(field) + key).toSet
      case JsonValue.Array(items) => items.flatMap(keysOf).toSet
      case _ => Set.empty
    }

  private def jsonOutputWithEveryKey: JsonValue = {
    val json = new JsonSink
    val consolidation = Consolidation.start(
      new OutputSink,
      coloured = false,
      jsonOutput = Some(json),
      moduleSelectors = Vector(moduleSelectedAsItself),
      buildTool = Some(BuildTool("Mill", "1.3.1")),
      startedAt = Some(Instant.ofEpochMilli(1000L))
    )
    consolidation.timeModule(moduleLabel, Some(Instant.ofEpochMilli(1100L)), Some(Instant.ofEpochMilli(1900L)))
    consolidation.addRootOutput(CapturedLine.Stderr("discovering\n"))
    consolidation.addRootFailure("discovery failed")
    consolidation.addRun(moduleLabel) { feed =>
      runEventsWithEveryKey.foreach(event => feed.add(event))
      feed.add(CapturedLine.Stdout("dividing\n"))
    }
    consolidation.end(Some(Instant.ofEpochMilli(2000L)))
    json.root
  }

  private def runEventsWithEveryKey: Vector[RunEvent] = {
    val notated = eventsOf(
      """
      run started
      structure
        suite "RowSuite", class "samples.RowSuite"
          scope "A row", label "A row"
            test "adds", label "- adds"
      structure
        suite "SkippedSuite", class "samples.SkippedSuite"
      suite "RowSuite": SuiteStarting(threadName: "pool-1")
      scope "A row": ScopeOpened(location: TopOfMethod("samples.RowSuite", "def row(): Unit"))
      scope "A row": InfoProvided(message: "in a scope", throwable: java.lang.IllegalStateException "checked")
      test "adds": TestStarting(location: LineInFile(7, "RowSuite.scala", Some("/build/RowSuite.scala")), threadName: "pool-1")
      test "adds": MarkupProvided(text: "*markup*")
      test "adds": TestFailed(message: "1 did not equal 2", throwable: org.scalatest.exceptions.TestFailedException "1 did not equal 2" at /build/RowSuite.scala:8, duration: 5)
      scope "A row": ScopeClosed
      suite "RowSuite": SuiteCompleted(duration: 9)
      run: InfoProvided(message: "outside any suite", location: LineInFile(3, "RowSuite.scala", None))
      run ended aborted
      """
    ).map(RunEvent.of)
    val shared = new DecodedThrowable("java.io.IOException", None, None, false, Vector.empty)
    val thrown = new DecodedThrowable(
      "java.lang.IllegalStateException",
      Some("boom"),
      None,
      false,
      Vector(
        DecodedStackFrame("samples.Row", "add", Some("Row.scala"), Some(3), false, "samples.Row.add(Row.scala:3)"),
        DecodedStackFrame("java.lang.Thread", "sleep", None, Some(-2), true, "java.lang.Thread.sleep(Native Method)")
      ),
      Some(shared),
      Vector(shared)
    )
    val runLevelEvents = Vector(
      DecodedEvent.RunStarting(3, Map("zone" -> "UTC"), None, "main", 100L),
      DecodedEvent.DiscoveryStarting("main", 110L),
      DecodedEvent.DiscoveryCompleted(Some(15L), "main", 125L)
    ).map(runEvent)
    val runAborted = runEvent(DecodedEvent.RunAborted("boom", Some(thrown), Some(400L), Some(Summary(1, 2, 3, 4, 5, 6, 7, 8)), None, "main", 500L))
    (notated.head +: runLevelEvents) ++ notated.tail.init ++ Vector(runAborted, notated.last)
  }

  Feature("The Mill plugin's documents describe the JSON output") {

    Scenario("The README links JSON.md") {
      Given("the Mill plugin's README")
      val readme = millPluginDocument("README.md")

      Then("it links JSON.md")
      readme should include("](JSON.md)")
    }

    Scenario("JSON.md defines every key the JSON output holds") {
      Given("JSON output holding every kind of node and record, each with every key the JSON output can give it")
      val keys = keysOf(jsonOutputWithEveryKey)

      When("the keys JSON.md defines are read")
      val defined = keysDefinedIn(millPluginDocument("JSON.md"))

      Then("JSON.md defines each of them")
      keys.diff(defined) shouldBe empty
    }
  }
}
