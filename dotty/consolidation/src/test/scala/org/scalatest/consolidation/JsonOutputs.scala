package org.scalatest.consolidation

import scala.collection.mutable
import org.scalatest.Assertions
import org.scalatest.events.DecodedEvent
import org.scalatest.events.JsonReader
import org.scalatest.events.JsonValue
import org.scalatest.events.TestEventLogEvent
import org.scalatest.events.TestRunBuilder
import org.scalatest.events.TestRunPart

private[consolidation] trait JsonOutputs { this: Assertions & ConsolidatedRuns =>

  val moduleSelectedAsItself: ModuleSelector = ModuleSelector("core.test", Vector(module))

  final class JsonSink extends (JsonOutput => Unit) {

    private val received = mutable.ArrayBuffer.empty[String]

    def apply(json: JsonOutput): Unit = {
      val text = new StringBuilder
      json.writeTo(piece => text.append(piece))
      synchronized(received += text.toString)
    }

    def texts: Vector[String] = synchronized(received.toVector)

    def text: String =
      texts match {
        case Vector(only) => only
        case other => fail(s"expected the JSON output written once, but it was written ${other.size} times")
      }

    def root: JsonValue = JsonReader.read(text)

    def module: JsonValue = root("contents").only

    def runNode: JsonValue = module("contents").only

    def node(kind: NodeKind, name: String): JsonValue =
      nodes(kind, name) match {
        case Vector(only) => only
        case other => fail(s"expected one ${kind.word} named $name, but found ${other.size}")
      }

    def nodes(kind: NodeKind, name: String): Vector[JsonValue] =
      allNodesUnder(root).filter(found => found.get("kind").contains(JsonValue.Text(kind.word)) && found.get("name").contains(JsonValue.Text(name)))

    private def allNodesUnder(parent: JsonValue): Vector[JsonValue] =
      parent.get("contents").fold(Vector.empty[JsonValue])(_.items.flatMap(child => child +: allNodesUnder(child)))
  }

  def startedWithoutMill(json: JsonSink): Consolidation =
    Consolidation.start(new OutputSink, coloured = false, jsonOutput = Some(json), moduleSelectors = Vector(moduleSelectedAsItself))

  def jsonOutputAfter(act: Consolidation => Unit): JsonSink = {
    val json = new JsonSink
    val consolidation = startedWithoutMill(json)
    act(consolidation)
    consolidation.end()
    json
  }

  def jsonOutputOfRun(parts: TestRunPart*): JsonSink = jsonOutputAfter(_.addRunOf(parts*))

  def jsonOutputOfRun(events: Vector[RunEvent]): JsonSink = jsonOutputAfter(_.addRunOf(events))

  def eventsOf(eventLog: String): Vector[TestEventLogEvent] =
    TestRunBuilder.build(TestRunPart.Events(eventLog)).events.map(_.event)

  def runEvent(event: DecodedEvent): RunEvent = RunEvent.of(TestEventLogEvent.ScalaTestEvent(None, event))

  def text(value: String): JsonValue = JsonValue.Text(value)

  def integer(value: Long): JsonValue = JsonValue.Integer(value)

  def objectOf(fields: (String, JsonValue)*): JsonValue = JsonValue.Object(fields.toVector)

  def arrayOf(items: JsonValue*): JsonValue = JsonValue.Array(items.toVector)

  extension (value: JsonValue) {

    def keys: Vector[String] =
      value match {
        case JsonValue.Object(fields) => fields.map(_._1)
        case other => fail(s"expected an object, but found $other")
      }

    def get(key: String): Option[JsonValue] =
      value match {
        case JsonValue.Object(fields) => fields.collectFirst { case (`key`, found) => found }
        case other => fail(s"expected an object, but found $other")
      }

    def apply(key: String): JsonValue = get(key).getOrElse(fail(s"expected the key $key among ${value.keys}"))

    def items: Vector[JsonValue] =
      value match {
        case JsonValue.Array(items) => items
        case other => fail(s"expected an array, but found $other")
      }

    def only: JsonValue =
      items match {
        case Vector(item) => item
        case other => fail(s"expected one item, but found ${other.size}")
      }
  }
}
