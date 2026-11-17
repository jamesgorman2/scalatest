package org.scalatest.tools

/**
 * A node path as the format writes it: its places from its top-level suite down. No suite ID, scope text or test name
 * may need escaping.
 */
private[tools] final case class NodePath(places: Vector[String]) {

  /** The path of a suite nested in this suite or test, the `index`th under it of its suite ID. */
  def suite(suiteId: String, index: Int = 0): NodePath = NodePath(places :+ s"""{"suiteId":"$suiteId","index":$index}""")

  /** The path of a scope directly under this suite or scope, the `index`th under it of its registered text. */
  def scope(text: String, index: Int = 0): NodePath = NodePath(places :+ s"""{"scope":"$text","index":$index}""")

  /** The path of a test of this suite. */
  def test(testName: String): NodePath = NodePath(places :+ s"""{"test":"$testName"}""")

  /** The path less its last place: a suite's or scope's parent. */
  def parent: NodePath = NodePath(places.init)

  def json: String = places.mkString("[", ",", "]")
}

private[tools] object NodePath {

  /** The path of a top-level suite, the `index`th of the run's top-level suites of its suite ID. */
  def topLevel(suiteId: String, index: Int = 0): NodePath = NodePath(Vector.empty).suite(suiteId, index)
}

private[tools] object TestEventLogLine {

  def runStarted(pid: Long, startTime: String): String = s"""{"item":"runStarted","pid":$pid,"startTime":"$startTime"}"""

  def runEnded(outcome: String): String = s"""{"item":"runEnded","outcome":"$outcome"}"""

  /** A `structure` event's line, given each node's text. No name, label, suite ID or class name may need escaping. */
  def structure(nodes: String*): String = s"""{"item":"structure","nodes":[${nodes.mkString(",")}]}"""

  /** A top-level suite, whose suite ID is its class name and whose path is that suite ID's first. */
  def suite(name: String, className: String): String = suiteWithId(NodePath.topLevel(className), name, className, className)

  /** A nested suite, whose suite ID is its class name. */
  def nestedSuite(id: NodePath, position: Int, name: String, className: String): String =
    s"""{"id":${id.json},"kind":"suite","parent":${id.parent.json},"position":$position,"name":"$name","suiteId":"$className","className":"$className"}"""

  /** A top-level suite whose suite ID need not be its class name. */
  def suiteWithId(id: NodePath, name: String, suiteId: String, className: String): String =
    s"""{"id":${id.json},"kind":"suite","name":"$name","suiteId":"$suiteId","className":"$className"}"""

  /** A nested suite with no declared position, as one added after its parent's first structure. Its suite ID is its class name. */
  def suiteUnder(id: NodePath, name: String, className: String): String =
    s"""{"id":${id.json},"kind":"suite","parent":${id.parent.json},"name":"$name","suiteId":"$className","className":"$className"}"""

  def scope(id: NodePath, position: Int, name: String, label: String): String =
    s"""{"id":${id.json},"kind":"scope","parent":${id.parent.json},"position":$position,"name":"$name","label":"$label"}"""

  def test(id: NodePath, parent: NodePath, position: Int, name: String, label: String): String =
    s"""{"id":${id.json},"kind":"test","parent":${parent.json},"position":$position,"name":"$name","label":"$label"}"""

  /** A test the structure marks ignored. */
  def ignoredTest(id: NodePath, parent: NodePath, position: Int, name: String, label: String): String =
    s"""{"id":${id.json},"kind":"test","parent":${parent.json},"position":$position,"name":"$name","label":"$label","ignored":true}"""

  private val fieldsNoCriterionFixes = """"threadName":"any thread","timeStamp":1"""

  /** A ScalaTest event assigned to the run. `fields` holds the event's own fields, each followed by a comma. */
  def runEvent(eventName: String, fields: String = ""): String =
    s"""{"item":"event","event":"$eventName",$fields$fieldsNoCriterionFixes}"""

  /**
   * A ScalaTest event assigned to a node, with its execution path, from its top-level suite down. `fields` holds the
   * event's own fields, each followed by a comma.
   */
  def nodeEvent(node: NodePath, executions: Seq[Int], eventName: String, fields: String = ""): String =
    s"""{"item":"event","node":${node.json},"executions":${executions.mkString("[", ",", "]")},"event":"$eventName",$fields$fieldsNoCriterionFixes}"""
}
