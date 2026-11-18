package org.scalatest.consolidation

import org.scalatest.ScalaTestVersions
import org.scalatest.events.DecodedEvent
import org.scalatest.events.JsonValue
import org.scalatest.events.JsonWriter
import org.scalatest.events.LineInFile
import org.scalatest.events.Location
import org.scalatest.events.RunOutcome
import org.scalatest.events.SeeStackDepthException
import org.scalatest.events.StructureNode
import org.scalatest.events.Summary
import org.scalatest.events.TopOfClass
import org.scalatest.events.TopOfMethod

/**
 * Writes the JSON output: the root, its modules, their run nodes, and each run's suites, scopes and tests. Each node's keys are written in a fixed order,
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
        "contents" -> Some(JsonValue.Array(run.topLevelSuites.map(assembledNode))),
        "output" -> nonEmptyArray(run.output.collect { case RunOutput.Captured(line) => capturedOutputItem(line) })
      )
    )
  }

  private def assembledNode(assembled: AssembledNode): JsonValue = {
    val node = assembled.node
    val execution = assembled.execution
    val startedAt = execution.opening.map(_.timeStamp)
    val endedAt = execution.closing.map(_.timeStamp).orElse(execution.abortedAt)
    objectOf(
      Vector[Field](
        "kind" -> Some(JsonValue.Text(kindOf(node))),
        "name" -> Some(JsonValue.Text(assembled.name)),
        "label" -> labelOf(node).map(JsonValue.Text(_)),
        "suiteId" -> suiteIdOf(node).map(JsonValue.Text(_)),
        "location" -> locationOf(node, execution),
        "outcome" -> execution.outcome.map(outcome => JsonValue.Text(outcomeText(outcome))),
        "reason" -> execution.outcome.filter(_ == NodeOutcome.NotRun).map(_ => JsonValue.Text("notRun")),
        "message" -> execution.ending.map(ending => JsonValue.Text(messageOf(ending))),
        "startedAt" -> startedAt.map(JsonValue.Integer(_)),
        "endedAt" -> endedAt.map(JsonValue.Integer(_)),
        "duration" -> durationOf(node, execution, startedAt, endedAt).map(JsonValue.Integer(_)),
        "threadName" -> threadNameOf(node, execution).map(JsonValue.Text(_)),
        "contents" -> Some(JsonValue.Array(execution.children.map(assembledNode)))
      )
    )
  }

  private def kindOf(node: StructureNode): String =
    node match {
      case _: StructureNode.Suite => "suite"
      case _: StructureNode.Scope => "scope"
      case _: StructureNode.Test => "test"
    }

  private def labelOf(node: StructureNode): Option[String] =
    node match {
      case _: StructureNode.Suite => None
      case scope: StructureNode.Scope => Some(scope.label)
      case test: StructureNode.Test => Some(test.label)
    }

  private def suiteIdOf(node: StructureNode): Option[String] =
    node match {
      case suite: StructureNode.Suite => Some(suite.suiteId)
      case _ => None
    }

  private def locationOf(node: StructureNode, execution: AssembledExecution): Option[JsonValue] =
    node match {
      case suite: StructureNode.Suite => Some(suiteLocationObject(suite, execution))
      case _ => scopeOrTestLocation(execution).flatMap(locationObject)
    }

  // A suite's or test's closing event measures its duration. A scope's closing event carries none, and a node aborted
  // with no closing event of its own has none to carry it.
  private def durationOf(node: StructureNode, execution: AssembledExecution, startedAt: Option[Long], endedAt: Option[Long]): Option[Long] = {
    val measured = for (started <- startedAt; ended <- endedAt) yield ended - started
    node match {
      case _: StructureNode.Scope => measured
      case _ => execution.closing.fold(measured)(_.duration)
    }
  }

  private def threadNameOf(node: StructureNode, execution: AssembledExecution): Option[String] =
    node match {
      case _: StructureNode.Scope => None
      case _ => execution.opening.orElse(execution.closing).map(_.threadName)
    }

  private def outcomeText(outcome: NodeOutcome): String =
    outcome match {
      case NodeOutcome.Succeeded => "succeeded"
      case NodeOutcome.Failed => "failed"
      case NodeOutcome.Ignored => "ignored"
      case NodeOutcome.Pending => "pending"
      case NodeOutcome.Canceled => "canceled"
      case NodeOutcome.Aborted | NodeOutcome.NotRun => "aborted"
    }

  private def messageOf(ending: DecodedEvent): String =
    ending match {
      case failed: DecodedEvent.TestFailed => failed.message
      case canceled: DecodedEvent.TestCanceled => canceled.message
      case aborted: DecodedEvent.SuiteAborted => aborted.message
      case other => throw new IllegalArgumentException(s"$other ends no suite, scope or test with a message")
    }

  // A `SeeStackDepthException` leaves the location to the throwable, so the closing event's is taken in its place.
  private def scopeOrTestLocation(execution: AssembledExecution): Option[Location] =
    (execution.opening ++ execution.closing).flatMap(_.location).find(_ != SeeStackDepthException)

  // A suite's events give no line of its own, so only the file its scopes' and tests' locations all name is written.
  private def suiteLocationObject(suite: StructureNode.Suite, execution: AssembledExecution): JsonValue = {
    val locations = ownScopesAndTestsOf(execution).flatMap(child => scopeOrTestLocation(child.execution))
    val files = locations.map(fileFields).distinct
    val sharedFile = files match {
      case Vector(file) if file.nonEmpty => file
      case _ => Vector.empty
    }
    objectOf(("className" -> Some(JsonValue.Text(suite.className))) +: sharedFile)
  }

  private def ownScopesAndTestsOf(execution: AssembledExecution): Vector[AssembledNode] =
    execution.children.filterNot(_.node.isInstanceOf[StructureNode.Suite]).flatMap(child => child +: ownScopesAndTestsOf(child.execution))

  private def locationObject(location: Location): Option[JsonValue] =
    location match {
      case TopOfClass(className) =>
        Some(objectOf(Vector[Field]("className" -> Some(JsonValue.Text(className)))))
      case TopOfMethod(className, methodId) =>
        Some(objectOf(Vector[Field]("className" -> Some(JsonValue.Text(className)), "methodId" -> Some(JsonValue.Text(methodId)))))
      case line: LineInFile =>
        Some(objectOf(fileFields(line) :+ ("lineNumber" -> Some(JsonValue.Integer(line.lineNumber.toLong)))))
      case SeeStackDepthException =>
        None
    }

  private def fileFields(location: Location): Vector[Field] =
    location match {
      case line: LineInFile =>
        Vector(line.filePathname.fold[Field]("fileName" -> Some(JsonValue.Text(line.fileName)))(path => "filePath" -> Some(JsonValue.Text(path))))
      case _ =>
        Vector.empty
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
