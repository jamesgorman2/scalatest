package org.scalatest.compatibility

import org.scalatest.events.DecodedEvent
import org.scalatest.events.DecodedThrowable
import org.scalatest.events.NodeExecution
import org.scalatest.events.NodePlace
import org.scalatest.events.NotationText.quoted
import org.scalatest.events.TestEventLogEvent

import java.util.IdentityHashMap

object EventLogFields {

  // Must write an event as compatibility.recorders.EventFieldsRecordingReporter writes the events it receives.
  def of(logged: TestEventLogEvent.ScalaTestEvent, withTimeStamp: Boolean): String = {
    val event = logged.event
    val fields = event.productElementNames
      .zip(event.productIterator)
      .collect { case (name, value) if withTimeStamp || name != "timeStamp" => name + "=" + rendered(value) }
    node(logged.execution) + " " + event.productPrefix + fields.mkString("(", ", ", ")")
  }

  def quotedSuiteIdOrRun(written: String): String =
    if (written.startsWith("run ")) {
      "run"
    } else {
      val opening = written.indexOf('"')
      var closing = opening + 1
      while (written.charAt(closing) != '"') {
        closing += (if (written.charAt(closing) == '\\') 2 else 1)
      }
      written.substring(opening, closing + 1)
    }

  private def node(execution: Option[NodeExecution]): String =
    execution.fold("run") { assigned =>
      val places = assigned.node.places
      val suiteId = places.collect { case NodePlace.Suite(id, _) => id }.last
      places.last match {
        case NodePlace.Test(testName) => "test " + quoted(suiteId.value) + " " + quoted(testName.value)
        case _ => "suite " + quoted(suiteId.value)
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

enum EmitterOrder {
  case AcrossTheRun
  case WithinEachSuite
}

object EventLogCoverage {

  private val receivedPrefix = "received "
  private val recordedPrefix = "recorded "

  def missingEvents(recording: Seq[String], eventLog: Seq[TestEventLogEvent.ScalaTestEvent], order: EmitterOrder): Seq[String] = {
    val logged = eventLog.toVector
    val withTimeStamps = logged.map(EventLogFields.of(_, withTimeStamp = true))
    val withoutTimeStamps = logged.map(EventLogFields.of(_, withTimeStamp = false))
    val held = Array.fill(logged.size)(false)
    val after = scala.collection.mutable.Map.empty[String, Int]

    def orderedWith(event: String): String =
      order match {
        case EmitterOrder.AcrossTheRun => ""
        case EmitterOrder.WithinEachSuite => EventLogFields.quotedSuiteIdOrRun(event)
      }

    val missingReceived = recording.filter(_.startsWith(receivedPrefix)).map(_.stripPrefix(receivedPrefix)).flatMap { event =>
      val group = orderedWith(event)
      val at = withTimeStamps.indices.find(index =>
        index >= after.getOrElse(group, 0) && withTimeStamps(index) == event && orderedWith(withTimeStamps(index)) == group
      )
      at match {
        case Some(index) =>
          held(index) = true
          after(group) = index + 1
          None
        case None =>
          val elsewhere = if (withTimeStamps.contains(event)) ", though the log holds it earlier" else ""
          Some(s"received, not in the event log in the emitter's order$elsewhere: $event")
      }
    }

    val missingRecorded = recording.filter(_.startsWith(recordedPrefix)).map(_.stripPrefix(recordedPrefix)).flatMap { event =>
      val at = withoutTimeStamps.indices.find(index => !held(index) && withoutTimeStamps(index) == event)
      at.foreach(held(_) = true)
      if (at.isEmpty) Some(s"recorded, not in the event log: $event") else None
    }

    missingReceived ++ missingRecorded
  }

  def suitesStartedAsReceived(recording: Seq[String]): Seq[String] =
    recording
      .filter(_.startsWith(receivedPrefix))
      .map(_.stripPrefix(receivedPrefix))
      .filter(event => event.startsWith(s"suite ${EventLogFields.quotedSuiteIdOrRun(event)} SuiteStarting("))
      .map(EventLogFields.quotedSuiteIdOrRun)

  def suitesStartedAsLogged(eventLog: Seq[TestEventLogEvent.ScalaTestEvent]): Seq[String] =
    eventLog
      .filter(_.event.isInstanceOf[DecodedEvent.SuiteStarting])
      .map(logged => EventLogFields.quotedSuiteIdOrRun(EventLogFields.of(logged, withTimeStamp = false)))
}
