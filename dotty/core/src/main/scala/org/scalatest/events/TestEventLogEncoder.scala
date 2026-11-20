package org.scalatest.events

import org.scalatest.ConfigMap
import org.scalatest.exceptions.PropertyCheckFailedException
import org.scalatest.exceptions.StackDepth
import scala.collection.mutable

/** @param fields the event's own fields, as the members of a JSON object without its braces */
private[scalatest] final class EncodedEvent private[events] (private[events] val name: String, private[events] val fields: String)

private[scalatest] object TestEventLogEncoder {

  private type Field = Option[(String, JsonValue)]

  def runStarted(pid: Long, startTime: String): String =
    line(text("item", "runStarted"), integer("pid", pid), text("startTime", startTime))

  def structure(nodes: Seq[StructureNode]): String =
    line(text("item", "structure"), array("nodes", nodes.map(node)))

  def event(event: Event, execution: Option[NodeExecution]): String = line(encoded(event), execution)

  /** Calls the methods of the event's throwables and config map values, which are the run's own code. */
  def encoded(event: Event): EncodedEvent = {
    val (eventName, eventFields) = nameAndFields(event)
    val members = JsonWriter.write(record(eventFields: _*))
    new EncodedEvent(eventName, members.substring(1, members.length - 1))
  }

  /** Calls none of the run's own code. */
  def line(encoded: EncodedEvent, execution: Option[NodeExecution]): String = {
    val assigned =
      JsonWriter.write(
        record(
          text("item", "event"),
          nested("node", execution.map(assigned => nodePath(assigned.node))),
          array("executions", execution.toVector.flatMap(_.executionPath).map(index => JsonValue.Integer(index.toLong))),
          text("event", encoded.name)
        )
      )
    val fields = if (encoded.fields.isEmpty) "" else "," + encoded.fields
    assigned.substring(0, assigned.length - 1) + fields + "}\n"
  }

  def runEnded(outcome: RunOutcome): String = line(text("item", "runEnded"), text("outcome", outcome.word))

  private def line(fields: Field*): String = JsonWriter.write(record(fields: _*)) + "\n"

  private def record(fields: Field*): JsonValue = JsonValue.Object(fields.flatten.toVector)

  private def text(key: String, value: String): Field = Some(key -> JsonValue.Text(value))

  private def optionalText(key: String, value: Option[String]): Field = value.flatMap(text(key, _))

  private def integer(key: String, value: Long): Field = Some(key -> JsonValue.Integer(value))

  private def optionalInteger(key: String, value: Option[Long]): Field = value.flatMap(integer(key, _))

  private def trueOrAbsent(key: String, value: Boolean): Field =
    if (value) Some(key -> JsonValue.Bool(true)) else None

  private def nested(key: String, value: Option[JsonValue]): Field = value.map(key -> _)

  private def array(key: String, items: Seq[JsonValue]): Field =
    if (items.isEmpty) None else Some(key -> JsonValue.Array(items.toVector))

  private def node(node: StructureNode): JsonValue = {
    node match {
      case StructureNode.Suite(id, parent, position, name, suiteId, className) =>
        record(
          nested("id", Some(nodePath(id))),
          text("kind", "suite"),
          nested("parent", parent.map(nodePath)),
          optionalInteger("position", position.map(_.toLong)),
          text("name", name),
          text("suiteId", suiteId.value),
          text("className", className)
        )
      case StructureNode.Scope(id, parent, position, name, label) =>
        scopeOrTest("scope", id, parent, position, name, label, ignored = false)
      case StructureNode.Test(id, parent, position, name, label, ignored) =>
        scopeOrTest("test", id, parent, position, name, label, ignored)
    }
  }

  private def scopeOrTest(kind: String, id: NodeId, parent: NodeId, position: Int, name: String, label: String, ignored: Boolean): JsonValue =
    record(
      nested("id", Some(nodePath(id))),
      text("kind", kind),
      nested("parent", Some(nodePath(parent))),
      integer("position", position.toLong),
      text("name", name),
      text("label", label),
      trueOrAbsent("ignored", ignored)
    )

  private def nodePath(id: NodeId): JsonValue =
    JsonValue.Array(
      id.places.map {
        case NodePlace.Suite(suiteId, index) => record(text("suiteId", suiteId.value), integer("index", index.toLong))
        case NodePlace.Scope(scopeText, index) => record(text("scope", scopeText), integer("index", index.toLong))
        case NodePlace.Test(testName) => record(text("test", testName.value))
      }
    )

  private def nameAndFields(event: Event): (String, Seq[Field]) = {
    val throwables = new ThrowableEncoder
    def throwable(value: Option[Throwable]): Field = nested("throwable", value.map(throwables.encode))
    val origin = Seq(location(event.location), text("threadName", event.threadName), integer("timeStamp", event.timeStamp))
    event match {
      case _: TestStarting => "TestStarting" -> origin
      case test: TestSucceeded => "TestSucceeded" -> (duration(test.duration) +: origin)
      case test: TestFailed =>
        "TestFailed" -> (Seq(
          text("message", test.message),
          array("analysis", test.analysis.map(JsonValue.Text(_))),
          throwable(test.throwable),
          duration(test.duration)
        ) ++ origin)
      case _: TestIgnored => "TestIgnored" -> origin
      case test: TestPending => "TestPending" -> (duration(test.duration) +: origin)
      case test: TestCanceled =>
        "TestCanceled" -> (Seq(text("message", test.message), throwable(test.throwable), duration(test.duration)) ++ origin)
      case _: SuiteStarting => "SuiteStarting" -> origin
      case suite: SuiteCompleted => "SuiteCompleted" -> (duration(suite.duration) +: origin)
      case suite: SuiteAborted =>
        "SuiteAborted" -> (Seq(text("message", suite.message), throwable(suite.throwable), duration(suite.duration)) ++ origin)
      case run: RunStarting =>
        "RunStarting" -> (Seq(integer("testCount", run.testCount.toLong), configMap(run.configMap)) ++ origin)
      case run: RunCompleted => "RunCompleted" -> (Seq(duration(run.duration), summary(run.summary)) ++ origin)
      case run: RunStopped => "RunStopped" -> (Seq(duration(run.duration), summary(run.summary)) ++ origin)
      case run: RunAborted =>
        "RunAborted" -> (Seq(
          text("message", run.message),
          throwable(run.throwable),
          duration(run.duration),
          summary(run.summary)
        ) ++ origin)
      case provided: InfoProvided => "InfoProvided" -> (Seq(text("message", provided.message), throwable(provided.throwable)) ++ origin)
      case provided: AlertProvided => "AlertProvided" -> (Seq(text("message", provided.message), throwable(provided.throwable)) ++ origin)
      case provided: NoteProvided => "NoteProvided" -> (Seq(text("message", provided.message), throwable(provided.throwable)) ++ origin)
      case markup: MarkupProvided => "MarkupProvided" -> (text("text", markup.text) +: origin)
      case _: ScopeOpened => "ScopeOpened" -> origin
      case _: ScopeClosed => "ScopeClosed" -> origin
      case _: ScopePending => "ScopePending" -> origin
      case discovery: DiscoveryStarting =>
        "DiscoveryStarting" -> Seq(text("threadName", discovery.threadName), integer("timeStamp", discovery.timeStamp))
      case discovery: DiscoveryCompleted =>
        "DiscoveryCompleted" -> Seq(duration(discovery.duration), text("threadName", discovery.threadName), integer("timeStamp", discovery.timeStamp))
    }
  }

  private def duration(value: Option[Long]): Field = optionalInteger("duration", value)

  private def location(value: Option[Location]): Field = {
    nested(
      "location",
      value.map {
        case TopOfClass(className) => record(text("kind", "TopOfClass"), text("className", className))
        case TopOfMethod(className, methodId) =>
          record(text("kind", "TopOfMethod"), text("className", className), text("methodId", methodId))
        case LineInFile(lineNumber, fileName, filePathname) =>
          record(
            text("kind", "LineInFile"),
            integer("lineNumber", lineNumber.toLong),
            text("fileName", fileName),
            optionalText("filePathname", filePathname)
          )
        case SeeStackDepthException => record(text("kind", "SeeStackDepthException"))
      }
    )
  }

  private def summary(value: Option[Summary]): Field = {
    nested(
      "summary",
      value.map { summary =>
        record(
          integer("testsSucceededCount", summary.testsSucceededCount.toLong),
          integer("testsFailedCount", summary.testsFailedCount.toLong),
          integer("testsIgnoredCount", summary.testsIgnoredCount.toLong),
          integer("testsPendingCount", summary.testsPendingCount.toLong),
          integer("testsCanceledCount", summary.testsCanceledCount.toLong),
          integer("suitesCompletedCount", summary.suitesCompletedCount.toLong),
          integer("suitesAbortedCount", summary.suitesAbortedCount.toLong),
          integer("scopesPendingCount", summary.scopesPendingCount.toLong)
        )
      }
    )
  }

  private def configMap(value: ConfigMap): Field = {
    val entries = value.toVector.sortBy { case (key, _) => key }
    Some("configMap" -> JsonValue.Object(entries.map { case (key, entry) => key -> JsonValue.Text(String.valueOf(entry)) }))
  }

  // Holds the throwables one event has written, in the order written, so that a throwable's ID is its place among them.
  private final class ThrowableEncoder {

    private val written = mutable.ArrayBuffer.empty[Throwable]

    def encode(throwable: Throwable): JsonValue =
      refTo(throwable).getOrElse(OpenThrowable.closedFrom(opened(throwable))(linked => refTo(linked).toLeft(opened(linked))))

    private def refTo(throwable: Throwable): Option[JsonValue] = {
      val index = written.indexWhere(_ eq throwable)
      if (index >= 0) Some(record(integer("ref", index + 1L))) else None
    }

    private def opened(throwable: Throwable): OpenThrowable[Throwable, JsonValue] = {
      written += throwable
      new EncodingThrowable(
        Seq(
          integer("id", written.size.toLong),
          text("className", throwable.getClass.getName),
          optionalText("message", Option(throwable.getMessage)),
          stackDepth(throwable),
          trueOrAbsent("propertyCheck", throwable.isInstanceOf[PropertyCheckFailedException]),
          array("stackTrace", throwable.getStackTrace.toIndexedSeq.map(frame))
        ),
        Option(throwable.getCause),
        throwable.getSuppressed.iterator
      )
    }

    private def stackDepth(throwable: Throwable): Field = {
      throwable match {
        case failed: StackDepth =>
          Some(
            "stackDepth" -> record(
              optionalText("fileName", failed.failedCodeFileName),
              optionalText("filePathname", failed.failedCodeFilePathname),
              optionalInteger("lineNumber", failed.failedCodeLineNumber.map(_.toLong))
            )
          )
        case _ => None
      }
    }

    private def frame(element: StackTraceElement): JsonValue =
      record(
        text("className", element.getClassName),
        text("methodName", element.getMethodName),
        optionalText("fileName", Option(element.getFileName)),
        optionalInteger("lineNumber", Some(element.getLineNumber.toLong).filter(_ >= 0)),
        trueOrAbsent("isNative", element.isNativeMethod),
        text("text", element.toString)
      )
  }

  private final class EncodingThrowable(ownFields: Seq[Field], cause: Option[Throwable], suppressed: Iterator[Throwable])
      extends OpenThrowable[Throwable, JsonValue](cause, suppressed) {

    def closed: JsonValue = record((ownFields :+ nested("cause", linkedCause) :+ array("suppressed", linkedSuppressed)): _*)
  }
}
