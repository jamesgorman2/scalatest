package org.scalatest.tools

private[tools] final case class NodePath(places: Vector[String]) {

  def suite(suiteId: String, indexAmongSameId: Int = 0): NodePath = NodePath(places :+ s"""{"suiteId":"$suiteId","index":$indexAmongSameId}""")

  def scope(text: String, indexAmongSameText: Int = 0): NodePath = NodePath(places :+ s"""{"scope":"$text","index":$indexAmongSameText}""")

  def test(testName: String): NodePath = NodePath(places :+ s"""{"test":"$testName"}""")

  def parent: NodePath = NodePath(places.init)

  def json: String = places.mkString("[", ",", "]")
}

private[tools] object NodePath {

  def topLevel(suiteId: String, indexAmongSameId: Int = 0): NodePath = NodePath(Vector.empty).suite(suiteId, indexAmongSameId)
}

private[tools] object TestEventLogLine {

  def runStarted(pid: Long, startTime: String): String = s"""{"item":"runStarted","pid":$pid,"startTime":"$startTime"}"""

  def runEnded(outcome: String): String = s"""{"item":"runEnded","outcome":"$outcome"}"""

  def structure(nodes: String*): String = s"""{"item":"structure","nodes":[${nodes.mkString(",")}]}"""

  def topLevelSuite(name: String, className: String): String = topLevelSuiteWithId(NodePath.topLevel(className), name, className, className)

  def nestedSuite(id: NodePath, position: Int, name: String, className: String): String =
    s"""{"id":${id.json},"kind":"suite","parent":${id.parent.json},"position":$position,"name":"$name","suiteId":"$className","className":"$className"}"""

  def topLevelSuiteWithId(id: NodePath, name: String, suiteId: String, className: String): String =
    s"""{"id":${id.json},"kind":"suite","name":"$name","suiteId":"$suiteId","className":"$className"}"""

  def nestedSuiteWithoutPosition(id: NodePath, name: String, className: String): String =
    s"""{"id":${id.json},"kind":"suite","parent":${id.parent.json},"name":"$name","suiteId":"$className","className":"$className"}"""

  def scope(id: NodePath, position: Int, name: String, label: String): String =
    s"""{"id":${id.json},"kind":"scope","parent":${id.parent.json},"position":$position,"name":"$name","label":"$label"}"""

  def test(id: NodePath, parent: NodePath, position: Int, name: String, label: String): String =
    s"""{"id":${id.json},"kind":"test","parent":${parent.json},"position":$position,"name":"$name","label":"$label"}"""

  def ignoredTest(id: NodePath, parent: NodePath, position: Int, name: String, label: String): String =
    s"""{"id":${id.json},"kind":"test","parent":${parent.json},"position":$position,"name":"$name","label":"$label","ignored":true}"""

  private val fieldsStatedOnlyToDecode = """"threadName":"any thread","timeStamp":1"""

  def runEvent(eventName: String, fields: String = ""): String =
    s"""{"item":"event","event":"$eventName",$fields$fieldsStatedOnlyToDecode}"""

  def nodeEvent(node: NodePath, executions: Seq[Int], eventName: String, fields: String = ""): String =
    s"""{"item":"event","node":${node.json},"executions":${executions.mkString("[", ",", "]")},"event":"$eventName",$fields$fieldsStatedOnlyToDecode}"""
}
