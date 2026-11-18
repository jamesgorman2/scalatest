package org.scalatest.tools

/** Places run from the top-level suite down. No suite ID, scope text or test name may need escaping. */
private[tools] final case class NodePath(places: Vector[String]) {

  /** `index` is the suite's index among the suites of `suiteId` under this node. */
  def suite(suiteId: String, index: Int = 0): NodePath = NodePath(places :+ s"""{"suiteId":"$suiteId","index":$index}""")

  /** `index` is the scope's index among the scopes of `text` under this node. */
  def scope(text: String, index: Int = 0): NodePath = NodePath(places :+ s"""{"scope":"$text","index":$index}""")

  def test(testName: String): NodePath = NodePath(places :+ s"""{"test":"$testName"}""")

  def parent: NodePath = NodePath(places.init)

  def json: String = places.mkString("[", ",", "]")
}

private[tools] object NodePath {

  /** `index` is the suite's index among the run's top-level suites of `suiteId`. */
  def topLevel(suiteId: String, index: Int = 0): NodePath = NodePath(Vector.empty).suite(suiteId, index)
}

private[tools] object TestEventLogLine {

  def runStarted(pid: Long, startTime: String): String = s"""{"item":"runStarted","pid":$pid,"startTime":"$startTime"}"""

  def runEnded(outcome: String): String = s"""{"item":"runEnded","outcome":"$outcome"}"""

  /** No name, label, suite ID or class name may need escaping. */
  def structure(nodes: String*): String = s"""{"item":"structure","nodes":[${nodes.mkString(",")}]}"""

  def topLevelSuite(name: String, className: String): String = topLevelSuiteWithId(NodePath.topLevel(className), name, className, className)

  def nestedSuite(id: NodePath, position: Int, name: String, className: String): String =
    s"""{"id":${id.json},"kind":"suite","parent":${id.parent.json},"position":$position,"name":"$name","suiteId":"$className","className":"$className"}"""

  def topLevelSuiteWithId(id: NodePath, name: String, suiteId: String, className: String): String =
    s"""{"id":${id.json},"kind":"suite","name":"$name","suiteId":"$suiteId","className":"$className"}"""

  /** As a suite added after its parent's first structure is written. */
  def nestedSuiteWithoutPosition(id: NodePath, name: String, className: String): String =
    s"""{"id":${id.json},"kind":"suite","parent":${id.parent.json},"name":"$name","suiteId":"$className","className":"$className"}"""

  def scope(id: NodePath, position: Int, name: String, label: String): String =
    s"""{"id":${id.json},"kind":"scope","parent":${id.parent.json},"position":$position,"name":"$name","label":"$label"}"""

  def test(id: NodePath, parent: NodePath, position: Int, name: String, label: String): String =
    s"""{"id":${id.json},"kind":"test","parent":${parent.json},"position":$position,"name":"$name","label":"$label"}"""

  def ignoredTest(id: NodePath, parent: NodePath, position: Int, name: String, label: String): String =
    s"""{"id":${id.json},"kind":"test","parent":${parent.json},"position":$position,"name":"$name","label":"$label","ignored":true}"""

  private val fieldsNoCriterionFixes = """"threadName":"any thread","timeStamp":1"""

  /** `fields` holds the event's own fields, each followed by a comma. */
  def runEvent(eventName: String, fields: String = ""): String =
    s"""{"item":"event","event":"$eventName",$fields$fieldsNoCriterionFixes}"""

  /** `executions` runs from the top-level suite down. `fields` holds the event's own fields, each followed by a comma. */
  def nodeEvent(node: NodePath, executions: Seq[Int], eventName: String, fields: String = ""): String =
    s"""{"item":"event","node":${node.json},"executions":${executions.mkString("[", ",", "]")},"event":"$eventName",$fields$fieldsNoCriterionFixes}"""
}
