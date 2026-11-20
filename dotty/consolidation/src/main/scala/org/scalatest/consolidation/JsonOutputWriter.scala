package org.scalatest.consolidation

import java.time.Duration
import java.time.Instant
import java.util.IdentityHashMap
import org.scalactic.source.Position
import org.scalatest.ScalaTestVersions
import org.scalatest.events.DecodedStackDepth
import org.scalatest.events.DecodedStackFrame
import org.scalatest.events.DecodedThrowable
import org.scalatest.events.JsonValue
import org.scalatest.events.JsonWriter
import org.scalatest.events.LineInFile
import org.scalatest.events.Location
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

  def write(root: ConsolidatedRoot, output: String => Unit): Unit = {
    val text = new TextInPieces(output)
    JsonWriter.write(rootNode(root), text)
    text.flush()
  }

  // Gives the text to `output` in pieces of about `pieceLength` characters, never splitting a surrogate pair.
  private final class TextInPieces(output: String => Unit) extends Appendable {

    private val pieceLength = 8192
    private val piece = new java.lang.StringBuilder

    def append(char: Char): Appendable = {
      piece.append(char)
      if (piece.length >= pieceLength && !Character.isHighSurrogate(char)) flush()
      this
    }

    def append(text: CharSequence): Appendable = append(text, 0, text.length)

    def append(text: CharSequence, start: Int, end: Int): Appendable = {
      (start until end).foreach(index => append(text.charAt(index)))
      this
    }

    def flush(): Unit =
      if (piece.length > 0) {
        output(piece.toString)
        piece.setLength(0)
      }
  }

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
      "startedAt" -> times.startedAt.map(epochMillis),
      "endedAt" -> times.endedAt.map(epochMillis),
      "duration" -> times.duration.map(millis)
    )

  private def runNode(run: ConsolidatedRun): JsonValue = {
    val assembly = run.assembly
    val starting = assembly.runStarting
    val closing = assembly.closing
    objectOf(
      Vector[Field](
        "kind" -> Some(JsonValue.Text("run")),
        "number" -> Some(JsonValue.Integer(run.number.value.toLong)),
        "forkGroup" -> assembly.worker.map(worker => JsonValue.Integer(worker.forkGroup.position.toLong)),
        "testCount" -> starting.map(_.testCount).filter(_ != 0).map(count => JsonValue.Integer(count.toLong)),
        "configMap" -> starting.map(started => configMapObject(started.configMap)),
        "discovery" -> assembly.discovery.map(discoveryObject),
        "outcome" -> assembly.outcome.filter(_.isAbnormal).map(outcome => JsonValue.Text(outcome.word)),
        "message" -> assembly.runAborted.map(aborted => JsonValue.Text(aborted.message)),
        "throwable" -> assembly.runAborted.flatMap(_.throwable).map(throwableRecord),
        "startedAt" -> assembly.startedAt.map(epochMillis),
        "endedAt" -> closing.map(closed => epochMillis(closed.at)),
        "duration" -> closing.flatMap(_.duration).map(millis),
        "summary" -> closing.flatMap(_.summary).map(summaryObject),
        "contents" -> Some(JsonValue.Array(assembly.topLevelSuites.map(assembledNode))),
        "output" -> nonEmptyArray(assembly.output.map(runOutputItem))
      )
    )
  }

  private def assembledNode(assembled: AssembledNode): JsonValue = {
    val execution = assembled.execution
    val threadName = assembled.threadName
    val ending = execution.ending
    objectOf(
      Vector[Field](
        "kind" -> Some(JsonValue.Text(assembled.kind.word)),
        "name" -> Some(JsonValue.Text(assembled.name)),
        "label" -> assembled.label.map(JsonValue.Text(_)),
        "suiteId" -> assembled.suiteId.map(suiteId => JsonValue.Text(suiteId.value)),
        "location" -> locationOf(assembled),
        "outcome" -> execution.outcome.map(outcome => JsonValue.Text(outcomeText(outcome))),
        "reason" -> execution.outcome.filter(_ == NodeOutcome.NotRun).map(_ => JsonValue.Text("notRun")),
        "message" -> ending.map(failure => JsonValue.Text(failure.message)),
        "throwable" -> ending.flatMap(_.throwable).map(throwableRecord),
        "startedAt" -> assembled.startedAt.map(epochMillis),
        "endedAt" -> assembled.endedAt.map(epochMillis),
        "duration" -> assembled.duration.map(millis),
        "threadName" -> threadName.map(JsonValue.Text(_)),
        "contents" -> Some(JsonValue.Array(execution.children.map(assembledNode))),
        "output" -> nonEmptyArray(execution.messages.map(placed => messageItem(placed.message, threadName)))
      )
    )
  }

  private def locationOf(assembled: AssembledNode): Option[JsonValue] =
    assembled.node match {
      case suite: StructureNode.Suite => Some(suiteLocationObject(suite, assembled.execution))
      case _ => assembled.eventLocation.flatMap(locationObject)
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

  // A suite's events give no line of their own. Its location has a file only when all its scopes' and tests' locations name the same one.
  private def suiteLocationObject(suite: StructureNode.Suite, execution: AssembledExecution): JsonValue = {
    val locations = execution.ownScopesAndTests.flatMap(_.eventLocation)
    val files = locations.map(fileFields).distinct
    val sharedFile = files match {
      case Vector(file) if file.nonEmpty => file
      case _ => Vector.empty
    }
    objectOf(("className" -> Some(JsonValue.Text(suite.className))) +: sharedFile)
  }

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
        "startedAt" -> Some(epochMillis(discovery.startedAt)),
        "duration" -> discovery.duration.map(millis)
      )
    )

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
      case RunOutput.Message(message) => messageItem(message, nodeThreadName = None)
      case RunOutput.Captured(line) => capturedOutputItem(line)
    }

  // A message's thread name is written only where its node has none or a different one.
  private def messageItem(message: ProvidedMessage, nodeThreadName: Option[String]): JsonValue =
    objectOf(
      Vector[Field](
        "kind" -> Some(JsonValue.Text(message.kind.word)),
        "message" -> Some(JsonValue.Text(message.text)),
        "at" -> Some(epochMillis(message.at)),
        "location" -> message.location.flatMap(locationObject),
        "throwable" -> message.throwable.map(throwableRecord),
        "threadName" -> Option.when(!nodeThreadName.contains(message.threadName))(JsonValue.Text(message.threadName))
      )
    )

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
  private final case class VisitedThrowable(linkedThrowable: LinkedThrowable, entry: ThrowableEntry)

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
    val linkedByParent = visited.indices.groupBy(index => visited(index).linkedThrowable.parent.map(_.entryIndex))
    val written = new Array[JsonValue](visited.size)
    visited.indices.reverse.foreach { index =>
      val linked = linkedByParent.getOrElse(Some(index), Vector.empty)
      def recordsLinkedAs(link: ThrowableLink): Vector[JsonValue] = linked.filter(visited(_).linkedThrowable.isLinkedAs(link)).map(written(_)).toVector
      written(index) = visited(index).entry match {
        case ThrowableEntry.Ref(id) =>
          objectOf(Vector[Field]("ref" -> Some(JsonValue.Integer(id.toLong))))
        case ThrowableEntry.Recorded(id) =>
          throwableObject(id, visited(index).linkedThrowable.throwable, recordsLinkedAs(ThrowableLink.Cause).headOption, recordsLinkedAs(ThrowableLink.Suppressed))
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

  private def epochMillis(at: Instant): JsonValue = JsonValue.Integer(at.toEpochMilli)

  private def millis(duration: Duration): JsonValue = JsonValue.Integer(duration.toMillis)

  private def nonEmptyArray(items: Vector[JsonValue]): Option[JsonValue] = Option.when(items.nonEmpty)(JsonValue.Array(items))

  private def objectOf(fields: Vector[Field]): JsonValue =
    JsonValue.Object(fields.collect { case (key, Some(value)) => key -> value })
}
