package org.scalatest.consolidation

import java.io.StringWriter
import scala.collection.mutable
import org.scalatest.Assertions
import org.scalatest.events.DecodedEvent
import org.scalatest.events.JsonReader
import org.scalatest.events.JsonValue
import org.scalatest.events.TestEventLogEvent
import org.scalatest.events.TestRunBuilder
import org.scalatest.events.TestRunPart

private[consolidation] trait JsonOutputs { this: Assertions & ConsolidatedRuns =>

  val moduleSelectedAsItself: ModuleSelector = ModuleSelector("core.test", Vector(moduleLabel))

  final class JsonSink extends (JsonOutput => Unit) {

    private val received = mutable.ArrayBuffer.empty[String]

    def apply(json: JsonOutput): Unit = {
      val writer = new StringWriter
      json.writeTo(writer)
      synchronized(received += writer.toString)
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

    /** The one suite, scope or test of the kind and name anywhere in the JSON output. */
    def node(kind: String, name: String): JsonValue =
      nodes(kind, name) match {
        case Vector(only) => only
        case other => fail(s"expected one $kind named $name, but found ${other.size}")
      }

    /** Every suite, scope or test of the kind and name, in the order the JSON output holds them. */
    def nodes(kind: String, name: String): Vector[JsonValue] =
      allNodesUnder(root).filter(found => found.get("kind").contains(JsonValue.Text(kind)) && found.get("name").contains(JsonValue.Text(name)))

    private def allNodesUnder(parent: JsonValue): Vector[JsonValue] =
      parent.get("contents").fold(Vector.empty[JsonValue])(_.items.flatMap(child => child +: allNodesUnder(child)))
  }

  /** A consolidation started without Mill: module selector `core.test` resolving to module `core.test`, and no build tool or root time. */
  def startedWithoutMill(json: JsonSink): Consolidation =
    Consolidation.start(new OutputSink, coloured = false, jsonOutput = Some(json), moduleSelectors = Vector(moduleSelectedAsItself))

  /** The JSON output of a consolidation started without Mill, given one run of module `core.test` fed the parts. */
  def jsonOutputOfRun(parts: TestRunPart*): JsonSink = {
    val json = new JsonSink
    val consolidation = startedWithoutMill(json)
    addRun(consolidation, parts*).endEventStream()
    consolidation.end()
    json
  }

  /** The events of the test event log written in notation, in order, so a test can alter them before feeding them. */
  def eventsOf(eventLog: String): Vector[TestEventLogEvent] =
    TestRunBuilder.build(TestRunPart.Events(eventLog)).events.map(_.event)

  /** The JSON output of a consolidation started without Mill, given one run of module `core.test` fed the events. */
  def jsonOutputOfEvents(events: Vector[TestEventLogEvent]): JsonSink =
    jsonOutputOfRunEvents(events.map(RunEvent.of))

  /** The JSON output of a consolidation started without Mill, given one run of module `core.test` fed the run events. */
  def jsonOutputOfRunEvents(events: Vector[RunEvent]): JsonSink = {
    val json = new JsonSink
    consolidateRun(startedWithoutMill(json), events)
    json
  }

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
