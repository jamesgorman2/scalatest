package org.scalatest.consolidation

import java.io.Writer
import java.util.IdentityHashMap
import org.scalactic.source.Position
import org.scalatest.ScalaTestVersions
import org.scalatest.events.DecodedEvent
import org.scalatest.events.DecodedStackDepth
import org.scalatest.events.DecodedStackFrame
import org.scalatest.events.DecodedThrowable
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
 * Writes the JSON output: the root, its modules, their run nodes, and each run's suites, scopes and tests, with their messages and throwables.
 * Each node's keys are written in a fixed order, and a key whose value is absent is left out, so no `null` is written.
 */
private[consolidation] object JsonOutputWriter {

  private type Field = (String, Option[JsonValue])

  def write(root: ConsolidatedRoot, out: Writer): Unit = JsonWriter.write(rootNode(root), out)

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
        "throwable" -> run.runAborted.flatMap(_.throwable).map(throwableRecord),
        "startedAt" -> starting.map(started => JsonValue.Integer(started.timeStamp)),
        "endedAt" -> closing.map(closed => JsonValue.Integer(closed.timeStamp)),
        "duration" -> closing.flatMap(_.duration).map(JsonValue.Integer(_)),
        "summary" -> closing.flatMap(_.summary).map(summaryObject),
        "contents" -> Some(JsonValue.Array(run.topLevelSuites.map(assembledNode))),
        "output" -> nonEmptyArray(run.output.map(runOutputItem))
      )
    )
  }

  private def assembledNode(assembled: AssembledNode): JsonValue = {
    val node = assembled.node
    val execution = assembled.execution
    val startedAt = execution.opening.map(_.timeStamp)
    val endedAt = execution.closing.map(_.timeStamp).orElse(execution.abortedAt)
    val threadName = threadNameOf(node, execution)
    val ending = execution.ending.map(EndingReport.of)
    objectOf(
      Vector[Field](
        "kind" -> Some(JsonValue.Text(kindOf(node))),
        "name" -> Some(JsonValue.Text(assembled.name)),
        "label" -> labelOf(node).map(JsonValue.Text(_)),
        "suiteId" -> suiteIdOf(node).map(JsonValue.Text(_)),
        "location" -> locationOf(node, execution),
        "outcome" -> execution.outcome.map(outcome => JsonValue.Text(outcomeText(outcome))),
        "reason" -> execution.outcome.filter(_ == NodeOutcome.NotRun).map(_ => JsonValue.Text("notRun")),
        "message" -> ending.map(report => JsonValue.Text(report.message)),
        "throwable" -> ending.flatMap(_.throwable).map(throwableRecord),
        "startedAt" -> startedAt.map(JsonValue.Integer(_)),
        "endedAt" -> endedAt.map(JsonValue.Integer(_)),
        "duration" -> durationOf(node, execution, startedAt, endedAt).map(JsonValue.Integer(_)),
        "threadName" -> threadName.map(JsonValue.Text(_)),
        "contents" -> Some(JsonValue.Array(execution.children.map(assembledNode))),
        "output" -> nonEmptyArray(execution.messages.map(placed => messageItem(placed.event, threadName)))
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
      case _ => scopeOrTestLocation(node, execution).flatMap(locationObject)
    }

  // A suite's or test's duration comes from its closing event. A scope's closing event carries none, and an aborted node
  // may have no closing event of its own; either way the duration is endedAt less startedAt.
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

  private final case class EndingReport(message: String, throwable: Option[DecodedThrowable])

  private object EndingReport {

    def of(ending: DecodedEvent): EndingReport =
      ending match {
        case failed: DecodedEvent.TestFailed => EndingReport(failed.message, failed.throwable)
        case canceled: DecodedEvent.TestCanceled => EndingReport(canceled.message, canceled.throwable)
        case aborted: DecodedEvent.SuiteAborted => EndingReport(aborted.message, aborted.throwable)
        case other => throw new IllegalArgumentException(s"$other ends no suite, scope or test with a message")
      }
  }

  // Where an opening event's location is `SeeStackDepthException`, which defers to the throwable, the closing event's location is used.
  private def scopeOrTestLocation(node: StructureNode, execution: AssembledExecution): Option[Location] = {
    val events = node match {
      case _: StructureNode.Scope => execution.opening.toVector
      case _ => (execution.opening ++ execution.closing).toVector
    }
    events.flatMap(_.location).find(_ != SeeStackDepthException)
  }

  // A suite's events give no line of their own. Its location has a file only when all its scopes' and tests' locations name the same one.
  private def suiteLocationObject(suite: StructureNode.Suite, execution: AssembledExecution): JsonValue = {
    val locations = ownScopesAndTestsOf(execution).flatMap(child => scopeOrTestLocation(child.node, child.execution))
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
        Vector(fileField(Some(line.fileName), line.filePathname))
      case _ =>
        Vector.empty
    }

  // Code compiled without SCALACTIC_FILL_FILE_PATHNAMES set to yes carries Scalactic's placeholder text in place of the path.
  private def fileField(fileName: Option[String], filePathname: Option[String]): Field =
    filePathname
      .filterNot(_ == Position.unfilledFilePathname)
      .fold[Field]("fileName" -> fileName.map(JsonValue.Text(_)))(path => "filePath" -> Some(JsonValue.Text(path)))

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

  private def runOutputItem(output: RunOutput): JsonValue =
    output match {
      case RunOutput.Message(event) => messageItem(event, nodeThreadName = None)
      case RunOutput.Captured(line) => capturedOutputItem(line)
    }

  // A message's thread name is written only where its node has none or a different one.
  private def messageItem(event: DecodedEvent, nodeThreadName: Option[String]): JsonValue = {
    val message = ProvidedMessage.of(event)
    objectOf(
      Vector[Field](
        "kind" -> Some(JsonValue.Text(message.kind)),
        "message" -> Some(JsonValue.Text(message.text)),
        "at" -> Some(JsonValue.Integer(message.at)),
        "location" -> message.location.flatMap(locationObject),
        "throwable" -> message.throwable.map(throwableRecord),
        "threadName" -> Option.when(!nodeThreadName.contains(message.threadName))(JsonValue.Text(message.threadName))
      )
    )
  }

  private final case class ProvidedMessage(
    kind: String,
    text: String,
    at: Long,
    location: Option[Location],
    throwable: Option[DecodedThrowable],
    threadName: String
  )

  private object ProvidedMessage {

    def of(event: DecodedEvent): ProvidedMessage =
      event match {
        case info: DecodedEvent.InfoProvided => ProvidedMessage("info", info.message, info.timeStamp, info.location, info.throwable, info.threadName)
        case alert: DecodedEvent.AlertProvided => ProvidedMessage("alert", alert.message, alert.timeStamp, alert.location, alert.throwable, alert.threadName)
        case note: DecodedEvent.NoteProvided => ProvidedMessage("note", note.message, note.timeStamp, note.location, note.throwable, note.threadName)
        case markup: DecodedEvent.MarkupProvided => ProvidedMessage("markup", markup.text, markup.timeStamp, markup.location, None, markup.threadName)
        case other => throw new IllegalArgumentException(s"$other is not a message")
      }
  }

  private enum ThrowableLink {
    case Cause
    case Suppressed
  }

  /** A throwable's parent in the record: the parent's entry index, and whether the throwable is the parent's cause or one of its suppressed throwables. */
  private final case class ThrowableParent(entryIndex: Int, link: ThrowableLink)

  /** A throwable in a record, with its link to its parent, if any. */
  private final case class LinkedThrowable(throwable: DecodedThrowable, parent: Option[ThrowableParent]) {

    def isLinkedAs(link: ThrowableLink): Boolean = parent.exists(_.link == link)
  }

  /** A throwable's entry in its record: its first appearance, under a new id, or a ref to that id. */
  private enum ThrowableEntry {
    case Recorded(id: Int)
    case Ref(id: Int)
  }

  /** A throwable the record has visited, with the entry visiting it produced. */
  private final case class VisitedThrowable(throwable: LinkedThrowable, entry: ThrowableEntry)

  // Builds the record in two passes. Neither recurses, so a cause chain too long to recurse over is still written.
  //   1. Visit the throwables depth-first, cause before suppressed. A throwable seen for the first time, by identity, gets the
  //      next id; one seen again becomes a ref.
  //   2. Build the record from the last entry back to the first.
  private def throwableRecord(throwable: DecodedThrowable): JsonValue = {
    val ids = new IdentityHashMap[DecodedThrowable, Int]
    val visitedThrowables = Vector.newBuilder[VisitedThrowable]
    var entryCount = 0
    var pending = List(LinkedThrowable(throwable, None))
    while (pending.nonEmpty) {
      val current = pending.head
      pending = pending.tail
      if (ids.containsKey(current.throwable)) {
        visitedThrowables += VisitedThrowable(current, ThrowableEntry.Ref(ids.get(current.throwable)))
      } else {
        val id = ids.size + 1
        ids.put(current.throwable, id)
        visitedThrowables += VisitedThrowable(current, ThrowableEntry.Recorded(id))
        val linked = current.throwable.cause.map(LinkedThrowable(_, Some(ThrowableParent(entryCount, ThrowableLink.Cause)))).toList :::
          current.throwable.suppressed.toList.map(LinkedThrowable(_, Some(ThrowableParent(entryCount, ThrowableLink.Suppressed))))
        pending = linked ::: pending
      }
      entryCount += 1
    }
    val visited = visitedThrowables.result()
    val linkedByParent = visited.indices.groupBy(index => visited(index).throwable.parent.map(_.entryIndex))
    val written = new Array[JsonValue](visited.size)
    visited.indices.reverse.foreach { index =>
      val linked = linkedByParent.getOrElse(Some(index), Vector.empty)
      def recordsLinkedAs(link: ThrowableLink): Vector[JsonValue] = linked.filter(visited(_).throwable.isLinkedAs(link)).map(written(_)).toVector
      written(index) = visited(index).entry match {
        case ThrowableEntry.Ref(id) =>
          objectOf(Vector[Field]("ref" -> Some(JsonValue.Integer(id.toLong))))
        case ThrowableEntry.Recorded(id) =>
          throwableObject(id, visited(index).throwable.throwable, recordsLinkedAs(ThrowableLink.Cause).headOption, recordsLinkedAs(ThrowableLink.Suppressed))
      }
    }
    written(0)
  }

  private def throwableObject(id: Int, throwable: DecodedThrowable, cause: Option[JsonValue], suppressed: Vector[JsonValue]): JsonValue =
    objectOf(
      Vector[Field](
        "id" -> Some(JsonValue.Integer(id.toLong)),
        "className" -> Some(JsonValue.Text(throwable.className)),
        "message" -> throwable.message.map(JsonValue.Text(_)),
        "location" -> throwable.stackDepth.flatMap(failedCodeLocation),
        "stackTrace" -> Option.when(throwable.stackDepth.isEmpty)(JsonValue.Array(throwable.stackTrace.toVector.map(stackFrameObject))),
        "cause" -> cause,
        "suppressed" -> nonEmptyArray(suppressed)
      )
    )

  private def failedCodeLocation(stackDepth: DecodedStackDepth): Option[JsonValue] = {
    val fields = Vector[Field](
      fileField(stackDepth.fileName, stackDepth.filePathname),
      "lineNumber" -> stackDepth.lineNumber.map(line => JsonValue.Integer(line.toLong))
    )
    Option.when(fields.exists(_._2.nonEmpty))(objectOf(fields))
  }

  private def stackFrameObject(frame: DecodedStackFrame): JsonValue =
    objectOf(
      Vector[Field](
        "className" -> Some(JsonValue.Text(frame.className)),
        "methodName" -> Some(JsonValue.Text(frame.methodName)),
        "fileName" -> frame.fileName.map(JsonValue.Text(_)),
        "lineNumber" -> frame.lineNumber.filter(_ >= 0).map(line => JsonValue.Integer(line.toLong)),
        "isNative" -> Option.when(frame.isNative)(JsonValue.Bool(true))
      )
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
