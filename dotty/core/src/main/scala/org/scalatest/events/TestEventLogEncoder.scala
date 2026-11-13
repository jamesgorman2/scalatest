package org.scalatest.events

import org.scalatest.ConfigMap
import org.scalatest.exceptions.PropertyCheckFailedException
import org.scalatest.exceptions.StackDepth
import scala.collection.mutable

private[scalatest] object TestEventLogEncoder {

  private type Field = Option[(String, JsonValue)]

  def runStarted(pid: Long, startTime: String): String =
    line(text("item", "runStarted"), integer("pid", pid), text("startTime", startTime))

  def structure(nodes: Seq[StructureNode]): String =
    line(text("item", "structure"), array("nodes", nodes.map(node)))

  def event(event: Event, execution: Option[NodeExecution]): String = {
    val (eventName, eventFields) = nameAndFields(event)
    val allFields =
      Seq(
        text("item", "event"),
        optionalInteger("node", execution.map(_.node.value)),
        optionalInteger("execution", execution.map(_.execution.toLong)),
        optionalInteger("parentExecution", execution.flatMap(_.parentExecution).map(_.toLong)),
        text("event", eventName)
      ) ++ eventFields
    line(allFields: _*)
  }

  def runEnded(outcome: RunOutcome): String = {
    val outcomeName = outcome match {
      case RunOutcome.Completed => "completed"
      case RunOutcome.Aborted => "aborted"
      case RunOutcome.Stopped => "stopped"
    }
    line(text("item", "runEnded"), text("outcome", outcomeName))
  }

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
          integer("id", id.value),
          text("kind", "suite"),
          optionalInteger("parent", parent.map(_.value)),
          optionalInteger("position", position.map(_.toLong)),
          text("name", name),
          text("suiteId", suiteId),
          text("className", className)
        )
      case StructureNode.Scope(id, parent, position, name, label) =>
        scopeOrTest("scope", id, parent, position, name, label)
      case StructureNode.Test(id, parent, position, name, label) =>
        scopeOrTest("test", id, parent, position, name, label)
    }
  }

  private def scopeOrTest(kind: String, id: NodeId, parent: NodeId, position: Int, name: String, label: String): JsonValue =
    record(
      integer("id", id.value),
      text("kind", kind),
      integer("parent", parent.value),
      integer("position", position.toLong),
      text("name", name),
      text("label", label)
    )

  private def nameAndFields(event: Event): (String, Seq[Field]) = {
    val throwables = new ThrowableEncoder
    def throwable(value: Option[Throwable]): Field = nested("throwable", value.map(throwables.encode))
    val origin = Seq(location(event.location), text("threadName", event.threadName), integer("timeStamp", event.timeStamp))
    event match {
      case _: TestStarting => "TestStarting" -> origin
      case e: TestSucceeded => "TestSucceeded" -> (duration(e.duration) +: origin)
      case e: TestFailed =>
        "TestFailed" -> (Seq(
          text("message", e.message),
          array("analysis", e.analysis.map(JsonValue.Text(_))),
          throwable(e.throwable),
          duration(e.duration)
        ) ++ origin)
      case _: TestIgnored => "TestIgnored" -> origin
      case e: TestPending => "TestPending" -> (duration(e.duration) +: origin)
      case e: TestCanceled =>
        "TestCanceled" -> (Seq(text("message", e.message), throwable(e.throwable), duration(e.duration)) ++ origin)
      case _: SuiteStarting => "SuiteStarting" -> origin
      case e: SuiteCompleted => "SuiteCompleted" -> (duration(e.duration) +: origin)
      case e: SuiteAborted =>
        "SuiteAborted" -> (Seq(text("message", e.message), throwable(e.throwable), duration(e.duration)) ++ origin)
      case e: RunStarting =>
        "RunStarting" -> (Seq(integer("testCount", e.testCount.toLong), configMap(e.configMap)) ++ origin)
      case e: RunCompleted => "RunCompleted" -> (Seq(duration(e.duration), summary(e.summary)) ++ origin)
      case e: RunStopped => "RunStopped" -> (Seq(duration(e.duration), summary(e.summary)) ++ origin)
      case e: RunAborted =>
        "RunAborted" -> (Seq(
          text("message", e.message),
          throwable(e.throwable),
          duration(e.duration),
          summary(e.summary)
        ) ++ origin)
      case e: InfoProvided => "InfoProvided" -> (Seq(text("message", e.message), throwable(e.throwable)) ++ origin)
      case e: AlertProvided => "AlertProvided" -> (Seq(text("message", e.message), throwable(e.throwable)) ++ origin)
      case e: NoteProvided => "NoteProvided" -> (Seq(text("message", e.message), throwable(e.throwable)) ++ origin)
      case e: MarkupProvided => "MarkupProvided" -> (text("text", e.text) +: origin)
      case _: ScopeOpened => "ScopeOpened" -> origin
      case _: ScopeClosed => "ScopeClosed" -> origin
      case _: ScopePending => "ScopePending" -> origin
      case e: DiscoveryStarting =>
        "DiscoveryStarting" -> Seq(text("threadName", e.threadName), integer("timeStamp", e.timeStamp))
      case e: DiscoveryCompleted =>
        "DiscoveryCompleted" -> Seq(duration(e.duration), text("threadName", e.threadName), integer("timeStamp", e.timeStamp))
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

    // Keeps the throwables whose causes and suppressed throwables are still to write in a list rather than on the
    // call stack, so that no length of cause chain overflows it.
    def encode(throwable: Throwable): JsonValue = {
      refTo(throwable).getOrElse {
        val outermost = opened(throwable)
        var open = List(outermost)
        while (open.nonEmpty) {
          val innermost = open.head
          innermost.nextLinked() match {
            case Some(linked) =>
              refTo(linked) match {
                case Some(ref) => innermost.add(ref)
                case None => open = opened(linked) :: open
              }
            case None =>
              open = open.tail
              open.headOption.foreach(_.add(innermost.closed))
          }
        }
        outermost.closed
      }
    }

    private def refTo(throwable: Throwable): Option[JsonValue] = {
      val index = written.indexWhere(_ eq throwable)
      if (index >= 0) Some(record(integer("ref", index + 1L))) else None
    }

    private def opened(throwable: Throwable): OpenThrowable = {
      written += throwable
      new OpenThrowable(
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

  // A throwable whose own fields are written, taking its cause and then each suppressed throwable as they are written.
  private final class OpenThrowable(ownFields: Seq[Field], cause: Option[Throwable], suppressed: Iterator[Throwable]) {

    private var causeIsNext = cause.nonEmpty
    private var addsCause = false
    private var writtenCause: Option[JsonValue] = None
    private val writtenSuppressed = Vector.newBuilder[JsonValue]

    def nextLinked(): Option[Throwable] = {
      addsCause = causeIsNext
      causeIsNext = false
      if (addsCause) cause else suppressed.nextOption()
    }

    def add(linked: JsonValue): Unit = {
      if (addsCause) {
        writtenCause = Some(linked)
      } else {
        writtenSuppressed += linked
      }
    }

    def closed: JsonValue =
      record((ownFields :+ nested("cause", writtenCause) :+ array("suppressed", writtenSuppressed.result())): _*)
  }
}
