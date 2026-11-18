package org.scalatest.consolidation

import org.scalatest.ScalaTestVersions
import org.scalatest.events.JsonValue
import org.scalatest.events.JsonWriter
import org.scalatest.events.RunOutcome
import org.scalatest.events.Summary

/**
 * Writes the JSON output: the root, its modules and their run nodes. Each node's keys are written in a fixed order,
 * and a key whose value is absent is left out, so no `null` is written.
 */
private[consolidation] object JsonOutputWriter {

  private type Field = (String, Option[JsonValue])

  def text(root: ConsolidatedRoot): String = JsonWriter.write(rootNode(root))

  private def rootNode(root: ConsolidatedRoot): JsonValue =
    objectOf(
      Vector[Field](
        "kind" -> Some(JsonValue.Text("root")),
        "scalaTestVersion" -> Some(JsonValue.Text(ScalaTestVersions.ScalaTestVersion)),
        "buildTool" -> root.buildTool.map(buildToolObject),
        "moduleSelectors" -> Some(JsonValue.Array(root.moduleSelectors.toVector.map(moduleSelectorObject))),
        "outcome" -> Option.when(root.failures.nonEmpty)(JsonValue.Text("aborted")),
        "failures" -> nonEmptyArray(root.failures.map(failure => objectOf(Vector[Field]("message" -> Some(JsonValue.Text(failure))))))
      ) ++ measuredTimeFields(root.times) ++ Vector[Field](
        "contents" -> Some(JsonValue.Array(root.modules.map(moduleNode))),
        "output" -> nonEmptyArray(root.output.map(capturedOutputItem))
      )
    )

  private def buildToolObject(buildTool: BuildTool): JsonValue =
    objectOf(Vector[Field]("name" -> Some(JsonValue.Text(buildTool.name)), "version" -> Some(JsonValue.Text(buildTool.version))))

  private def moduleSelectorObject(moduleSelector: ModuleSelector): JsonValue =
    objectOf(
      Vector[Field](
        "selector" -> Some(JsonValue.Text(moduleSelector.selector)),
        "modules" -> Some(JsonValue.Array(moduleSelector.modules.map(module => JsonValue.Text(module.value))))
      )
    )

  private def moduleNode(module: ConsolidatedModule): JsonValue =
    objectOf(
      Vector[Field](
        "kind" -> Some(JsonValue.Text("module")),
        "name" -> Some(JsonValue.Text(module.label.value))
      ) ++ measuredTimeFields(module.times) :+
        ("contents" -> Some(JsonValue.Array(module.runs.map(runNode))))
    )

  private def measuredTimeFields(times: MeasuredTimes): Vector[Field] =
    Vector(
      "startedAt" -> times.startedAt.map(time => JsonValue.Integer(time.toEpochMilli)),
      "endedAt" -> times.endedAt.map(time => JsonValue.Integer(time.toEpochMilli)),
      "duration" -> times.duration.map(JsonValue.Integer(_))
    )

  private def runNode(run: RunAssembly): JsonValue = {
    val starting = run.runStarting
    val closing = run.closing
    objectOf(
      Vector[Field](
        "kind" -> Some(JsonValue.Text("run")),
        "number" -> Some(JsonValue.Integer(run.number.toLong)),
        "testCount" -> starting.map(_.testCount).filter(_ != 0).map(count => JsonValue.Integer(count.toLong)),
        "configMap" -> starting.map(started => configMapObject(started.configMap)),
        "discovery" -> run.discovery.map(discoveryObject),
        "outcome" -> run.outcome.flatMap(runOutcomeText).map(JsonValue.Text(_)),
        "message" -> run.runAborted.map(aborted => JsonValue.Text(aborted.message)),
        "startedAt" -> starting.map(started => JsonValue.Integer(started.timeStamp)),
        "endedAt" -> closing.map(closed => JsonValue.Integer(closed.timeStamp)),
        "duration" -> closing.flatMap(_.duration).map(JsonValue.Integer(_)),
        "summary" -> closing.flatMap(_.summary).map(summaryObject),
        "contents" -> Some(JsonValue.Array(Vector.empty)),
        "output" -> nonEmptyArray(run.output.collect { case RunOutput.Captured(line) => capturedOutputItem(line) })
      )
    )
  }

  private def configMapObject(configMap: Map[String, String]): JsonValue =
    JsonValue.Object(configMap.toVector.sortBy(_._1).map((key, value) => key -> JsonValue.Text(value)))

  private def discoveryObject(discovery: RunDiscovery): JsonValue =
    objectOf(
      Vector[Field](
        "startedAt" -> Some(JsonValue.Integer(discovery.startedAt)),
        "duration" -> discovery.duration.map(JsonValue.Integer(_))
      )
    )

  private def runOutcomeText(outcome: RunOutcome): Option[String] =
    outcome match {
      case RunOutcome.Aborted => Some("aborted")
      case RunOutcome.Stopped => Some("stopped")
      case RunOutcome.Completed => None
    }

  private def summaryObject(summary: Summary): JsonValue =
    JsonValue.Object(
      Vector(
        "testsSucceededCount" -> summary.testsSucceededCount,
        "testsFailedCount" -> summary.testsFailedCount,
        "testsIgnoredCount" -> summary.testsIgnoredCount,
        "testsPendingCount" -> summary.testsPendingCount,
        "testsCanceledCount" -> summary.testsCanceledCount,
        "suitesCompletedCount" -> summary.suitesCompletedCount,
        "suitesAbortedCount" -> summary.suitesAbortedCount,
        "scopesPendingCount" -> summary.scopesPendingCount
      ).map((key, count) => key -> JsonValue.Integer(count.toLong))
    )

  private def capturedOutputItem(line: CapturedLine): JsonValue = {
    val (stream, text) = line match {
      case CapturedLine.Stdout(text) => ("stdout", text)
      case CapturedLine.Stderr(text) => ("stderr", text)
    }
    objectOf(
      Vector[Field](
        "kind" -> Some(JsonValue.Text("stdio")),
        "stream" -> Some(JsonValue.Text(stream)),
        "text" -> Some(JsonValue.Text(text))
      )
    )
  }

  private def nonEmptyArray(items: Vector[JsonValue]): Option[JsonValue] = Option.when(items.nonEmpty)(JsonValue.Array(items))

  private def objectOf(fields: Vector[Field]): JsonValue =
    JsonValue.Object(fields.collect { case (key, Some(value)) => key -> value })
}
