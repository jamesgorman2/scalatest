package org.scalatest.consolidation

import java.time.Duration
import java.time.Instant
import org.scalactic.source.Position
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

private[consolidation] object JsonOutputWriter {

  private type Field = (String, Option[JsonValue])

  def write(root: ConsolidatedRoot, output: String => Unit): Unit = {
    val text = new TextInPieces(output)
    JsonWriter.write(rootNode(root), text)
    text.flush()
  }

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
        "scalaTestVersion" -> Some(JsonValue.Text(Consolidation.scalaTestVersion)),
        "buildTool" -> root.buildTool.map(_.toJson),
        "moduleSelectors" -> Some(JsonValue.Array(root.moduleSelectors.toVector.map(_.toJson)))
      ) ++ failureFields(root.attachments) ++ measuredTimeFields(root.times) ++ Vector[Field](
        "contents" -> Some(JsonValue.Array(root.modules.map(moduleNode))),
        "output" -> capturedOutputField(root.attachments)
      )
    )

  private def moduleNode(module: ConsolidatedModule): JsonValue =
    objectOf(
      Vector[Field](
        "kind" -> Some(JsonValue.Text("module")),
        "name" -> Some(JsonValue.Text(module.name.value))
      ) ++ failureFields(module.attachments) ++ measuredTimeFields(module.times) ++ Vector[Field](
        "contents" -> Some(JsonValue.Array(module.runs.map(runNode))),
        "output" -> capturedOutputField(module.attachments)
      )
    )

  private def failureFields(attachments: Attachments): Vector[Field] = {
    val failures = attachments.failures
    Vector(
      "outcome" -> Option.when(failures.nonEmpty)(JsonValue.Text("aborted")),
      "failures" -> nonEmptyArray(failures.map(failure => objectOf(Vector[Field]("message" -> Some(JsonValue.Text(failure))))))
    )
  }

  private def capturedOutputField(attachments: Attachments): Option[JsonValue] = nonEmptyArray(attachments.output.map(capturedOutputItem))

  private def measuredTimeFields(times: MeasuredTimes): Vector[Field] =
    Vector(
      "startedAt" -> times.startedAt.map(epochMillis),
      "endedAt" -> times.endedAt.map(epochMillis),
      "duration" -> times.duration.map(millis)
    )

  private def runNode(run: ConsolidatedRun): JsonValue = {
    val assembled = run.assembled
    val eventStream = assembled.eventStream
    val starting = eventStream.starting
    val closing = eventStream.closing
    objectOf(
      Vector[Field](
        "kind" -> Some(JsonValue.Text("run")),
        "number" -> Some(JsonValue.Integer(run.number.value.toLong)),
        "forkGroup" -> assembled.worker.map(worker => JsonValue.Integer(worker.forkGroup.position.toLong)),
        "testCount" -> starting.map(_.testCount).filter(_ != 0).map(count => JsonValue.Integer(count.toLong)),
        "configMap" -> starting.map(_.configMap.toJson),
        "discovery" -> eventStream.discovery.map(_.toJson),
        "outcome" -> eventStream.outcome.filter(_.isAbnormal).map(outcome => JsonValue.Text(outcome.word)),
        "message" -> eventStream.abort.map(aborted => JsonValue.Text(aborted.message)),
        "throwable" -> eventStream.abort.flatMap(_.throwable).map(throwableRecord),
        "startedAt" -> eventStream.startedAt.map(epochMillis),
        "endedAt" -> closing.map(closed => epochMillis(closed.at)),
        "duration" -> closing.flatMap(_.duration).map(millis),
        "summary" -> closing.flatMap(_.summary).map(_.toJson),
        "contents" -> Some(JsonValue.Array(eventStream.topLevelSuites.map(assembledNode))),
        "output" -> nonEmptyArray(assembled.output.map(runOutputItem))
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
      case suite: StructureNode.Suite => Some(suiteLocation(suite, assembled.execution))
      case _ => assembled.eventLocation.flatMap(_.toJson)
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

  private def suiteLocation(suite: StructureNode.Suite, execution: AssembledExecution): JsonValue = {
    // A suite's events name no file, so its location takes the file its scopes and tests share.
    val locations = execution.ownScopesAndTests.flatMap(_.eventLocation)
    val files = locations.map(fileFields).distinct
    val sharedFile = files match {
      case Vector(file) if file.nonEmpty => file
      case _ => Vector.empty
    }
    objectOf(("className" -> Some(JsonValue.Text(suite.className.value))) +: sharedFile)
  }

  extension (location: Location) {
    private def toJson: Option[JsonValue] =
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
  }

  private def fileFields(location: Location): Vector[Field] =
    location match {
      case line: LineInFile =>
        Vector(fileField(Some(line.fileName), line.filePathname))
      case _ =>
        Vector.empty
    }

  private def fileField(fileName: Option[String], filePathname: Option[String]): Field =
    filePathname
      .filterNot(_ == Position.unfilledFilePathname)
      .fold[Field]("fileName" -> fileName.map(JsonValue.Text(_)))(path => "filePath" -> Some(JsonValue.Text(path)))

  extension (configMap: Map[String, String]) {
    private def toJson: JsonValue =
      JsonValue.Object(configMap.toVector.sortBy((key, _) => key).map((key, value) => key -> JsonValue.Text(value)))
  }

  extension (summary: Summary) {
    private def toJson: JsonValue =
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
  }

  private def runOutputItem(output: RunOutput): JsonValue =
    output match {
      case RunOutput.Message(message) => messageItem(message, nodeThreadName = None)
      case RunOutput.Captured(line) => capturedOutputItem(line)
    }

  private def messageItem(message: ProvidedMessage, nodeThreadName: Option[String]): JsonValue =
    objectOf(
      Vector[Field](
        "kind" -> Some(JsonValue.Text(message.kind.word)),
        "message" -> Some(JsonValue.Text(message.text)),
        "at" -> Some(epochMillis(message.at)),
        "location" -> message.location.flatMap(_.toJson),
        "throwable" -> message.throwable.map(throwableRecord),
        "threadName" -> Option.when(!nodeThreadName.contains(message.threadName))(JsonValue.Text(message.threadName))
      )
    )

  private enum ThrowableEntry {
    case Recorded(id: Int, throwable: DecodedThrowable)
    case Ref(id: Int)
  }

  private final case class ThrowableVisit(pending: List[DecodedThrowable], ids: Map[DecodedThrowable, Int]) {

    def next: Option[(ThrowableEntry, ThrowableVisit)] =
      pending match {
        case Nil =>
          None
        case current :: rest =>
          // `DecodedThrowable` compares by identity, so only the same throwable seen again becomes a ref.
          ids.get(current) match {
            case Some(id) => Some(ThrowableEntry.Ref(id) -> copy(pending = rest))
            case None => Some(firstVisitTo(current, rest))
          }
      }

    private def firstVisitTo(throwable: DecodedThrowable, rest: List[DecodedThrowable]): (ThrowableEntry, ThrowableVisit) = {
      val id = ids.size + 1
      val linked = throwable.cause.toList ::: throwable.suppressed.toList
      ThrowableEntry.Recorded(id, throwable) -> ThrowableVisit(linked ::: rest, ids.updated(throwable, id))
    }
  }

  private def throwableRecord(throwable: DecodedThrowable): JsonValue =
    // Neither `entriesInVisitOrder` nor `recordOf` recurses, so a cause chain too long to recurse over is still written.
    recordOf(entriesInVisitOrder(throwable))

  private def entriesInVisitOrder(throwable: DecodedThrowable): Vector[ThrowableEntry] =
    Iterator.unfold(ThrowableVisit(List(throwable), Map.empty))(_.next).toVector

  private def recordOf(entries: Vector[ThrowableEntry]): JsonValue =
    entries.foldRight(List.empty[JsonValue])(withEntryWritten).head

  private def withEntryWritten(entry: ThrowableEntry, written: List[JsonValue]): List[JsonValue] =
    entry match {
      case ThrowableEntry.Ref(id) =>
        objectOf(Vector[Field]("ref" -> Some(JsonValue.Integer(id.toLong)))) :: written
      case ThrowableEntry.Recorded(id, throwable) => {
        // Entries were visited depth-first and are written last to first, so `written` starts with the record of this
        // throwable's cause, then those of its suppressed throwables.
        val (causes, afterCauses) = written.splitAt(throwable.cause.size)
        val (suppressed, rest) = afterCauses.splitAt(throwable.suppressed.size)
        recordedThrowable(id, throwable, causes.headOption, suppressed.toVector) :: rest
      }
    }

  private def recordedThrowable(id: Int, throwable: DecodedThrowable, cause: Option[JsonValue], suppressed: Vector[JsonValue]): JsonValue =
    objectOf(
      Vector[Field](
        "id" -> Some(JsonValue.Integer(id.toLong)),
        "className" -> Some(JsonValue.Text(throwable.className)),
        "message" -> throwable.message.map(JsonValue.Text(_)),
        "location" -> throwable.stackDepth.flatMap(failedCodeLocation),
        "stackTrace" -> Option.when(throwable.stackDepth.isEmpty)(JsonValue.Array(throwable.stackTrace.toVector.map(_.toJson))),
        "cause" -> cause,
        "suppressed" -> nonEmptyArray(suppressed)
      )
    )

  private def failedCodeLocation(stackDepth: DecodedStackDepth): Option[JsonValue] = {
    val fields = Vector[Field](
      fileField(stackDepth.fileName, stackDepth.filePathname),
      "lineNumber" -> stackDepth.lineNumber.map(line => JsonValue.Integer(line.toLong))
    )
    Option.when(fields.exists((_, value) => value.nonEmpty))(objectOf(fields))
  }

  extension (frame: DecodedStackFrame) {
    private def toJson: JsonValue =
      objectOf(
        Vector[Field](
          "className" -> Some(JsonValue.Text(frame.className)),
          "methodName" -> Some(JsonValue.Text(frame.methodName)),
          "fileName" -> frame.fileName.map(JsonValue.Text(_)),
          "lineNumber" -> frame.lineNumber.filter(_ >= 0).map(line => JsonValue.Integer(line.toLong)),
          "isNative" -> Option.when(frame.isNative)(JsonValue.Bool(true))
        )
      )
  }

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

  def epochMillis(at: Instant): JsonValue = JsonValue.Integer(at.toEpochMilli)

  def millis(duration: Duration): JsonValue = JsonValue.Integer(duration.toMillis)

  private def nonEmptyArray(items: Vector[JsonValue]): Option[JsonValue] = Option.when(items.nonEmpty)(JsonValue.Array(items))

  def objectOf(fields: Vector[Field]): JsonValue =
    JsonValue.Object(fields.collect { case (key, Some(value)) => key -> value })
}
