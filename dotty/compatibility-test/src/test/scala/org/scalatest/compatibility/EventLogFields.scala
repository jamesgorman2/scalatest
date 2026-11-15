package org.scalatest.compatibility

import org.scalatest.events.DecodedThrowable
import org.scalatest.events.NodeExecution
import org.scalatest.events.NodePlace
import org.scalatest.events.TestEventLogEvent

import java.util.IdentityHashMap

/**
 * Writes an event of a test event log as `compatibility.recorders.EventFieldsRecordingReporter` writes the event a
 * reporter receives: the node it is assigned to, named by its suite's ID and its test's name, then each field the
 * event log keeps of it.
 */
object EventLogFields {

  def of(logged: TestEventLogEvent.ScalaTestEvent, withTimeStamp: Boolean): String = {
    val event = logged.event
    val fields = event.productElementNames
      .zip(event.productIterator)
      .collect { case (name, value) if withTimeStamp || name != "timeStamp" => name + "=" + rendered(value) }
    node(logged.execution) + " " + event.productPrefix + fields.mkString("(", ", ", ")")
  }

  private def node(execution: Option[NodeExecution]): String =
    execution.fold("run") { assigned =>
      val places = assigned.node.places
      val suiteId = places.collect { case NodePlace.Suite(id, _) => id }.last
      places.last match {
        case NodePlace.Test(name) => "test " + quoted(suiteId) + " " + quoted(name)
        case _ => "suite " + quoted(suiteId)
      }
    }

  private def rendered(value: Any): String =
    value match {
      case Some(inner) => "Some(" + rendered(inner) + ")"
      case None => "None"
      case throwable: DecodedThrowable => new Throwables().rendered(throwable)
      case text: String => quoted(text)
      case entries: scala.collection.Map[?, ?] =>
        entries.toList.map((key, entry) => String.valueOf(key) -> String.valueOf(entry)).sortBy(_._1)
          .map((key, entry) => quoted(key) + " -> " + quoted(entry))
          .mkString("{", ", ", "}")
      case items: Iterable[?] => items.map(rendered).mkString("[", ", ", "]")
      case other => String.valueOf(other)
    }

  private def quoted(text: String): String =
    text.flatMap {
      case '"' => "\\\""
      case '\\' => "\\\\"
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case char if char < ' ' || Character.isSurrogate(char) => f"\\u${char.toInt}%04x"
      case char => char.toString
    }.mkString("\"", "", "\"")

  private final class Throwables {

    private val ids = new IdentityHashMap[DecodedThrowable, Integer]

    def rendered(throwable: DecodedThrowable): String =
      Option(ids.get(throwable)).fold(renderedFirstTime(throwable))(id => "ref " + id)

    private def renderedFirstTime(throwable: DecodedThrowable): String = {
      ids.put(throwable, ids.size + 1)
      val stackDepth = throwable.stackDepth.fold("") { failed =>
        " stackDepth(" + List(
          failed.fileName.map(quoted),
          failed.filePathname.map(quoted),
          failed.lineNumber.map(_.toString)
        ).map(_.getOrElse("?")).mkString(", ") + ")"
      }
      val propertyCheck = if (throwable.isPropertyCheckFailure) " propertyCheck" else ""
      val frames = throwable.stackTrace.toList.map { frame =>
        List(
          quoted(frame.className),
          quoted(frame.methodName),
          frame.fileName.fold("?")(quoted),
          frame.lineNumber.fold("?")(_.toString),
          String.valueOf(frame.isNative),
          quoted(frame.text)
        ).mkString("(", ", ", ")")
      }
      val cause = throwable.cause.fold("")(cause => " cause(" + rendered(cause) + ")")
      val suppressed = throwable.suppressed.toList.map(rendered)
      throwable.className + " " + throwable.message.fold("null")(quoted) + stackDepth + propertyCheck +
        " frames" + frames.mkString("[", ", ", "]") + cause + " suppressed" + suppressed.mkString("[", ", ", "]")
    }
  }
}

/**
 * Checks that a test event log holds every event a reporter of the same run received, and every event recorded in
 * one, as `compatibility.recorders.EventFieldsRecordingReporter` wrote them. The events received must be in the event
 * log in the order received; a recorded event may be anywhere in it.
 */
object EventLogCoverage {

  private val receivedPrefix = "received "
  private val recordedPrefix = "recorded "

  /** Describes each event the reporter's lines hold that the event log does not, in the order the reporter wrote them. */
  def missing(reporterLines: Seq[String], eventLog: Seq[TestEventLogEvent.ScalaTestEvent]): Seq[String] = {
    val logged = eventLog.toVector
    val withTimeStamps = logged.map(EventLogFields.of(_, withTimeStamp = true))
    val withoutTimeStamps = logged.map(EventLogFields.of(_, withTimeStamp = false))
    val held = Array.fill(logged.size)(false)
    var after = 0

    val missingReceived = reporterLines.filter(_.startsWith(receivedPrefix)).map(_.stripPrefix(receivedPrefix)).flatMap { event =>
      val at = withTimeStamps.indexWhere(_ == event, after)
      if (at < 0) {
        val elsewhere = if (withTimeStamps.contains(event)) ", though it is earlier" else ""
        Some(s"received, not in the event log in the order received$elsewhere: $event")
      } else {
        held(at) = true
        after = at + 1
        None
      }
    }

    val missingRecorded = reporterLines.filter(_.startsWith(recordedPrefix)).map(_.stripPrefix(recordedPrefix)).flatMap { event =>
      val at = withoutTimeStamps.indices.find(index => !held(index) && withoutTimeStamps(index) == event)
      at.foreach(held(_) = true)
      if (at.isEmpty) Some(s"recorded, not in the event log: $event") else None
    }

    missingReceived ++ missingRecorded
  }
}
