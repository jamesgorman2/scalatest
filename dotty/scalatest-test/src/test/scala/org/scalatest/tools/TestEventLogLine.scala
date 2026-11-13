package org.scalatest.tools

import scala.collection.mutable

private[tools] object TestEventLogLine {

  def runStarted(pid: Long, startTime: String): String = s"""{"item":"runStarted","pid":$pid,"startTime":"$startTime"}"""

  def runEnded(outcome: String): String = s"""{"item":"runEnded","outcome":"$outcome"}"""

  /** A `structure` event's line, given each node's text. No name, label, suite ID or class name may need escaping. */
  def structure(nodes: String*): String = s"""{"item":"structure","nodes":[${nodes.mkString(",")}]}"""

  /** A top-level suite, whose suite ID is its class name. */
  def suite(id: Long, name: String, className: String): String =
    s"""{"id":$id,"kind":"suite","name":"$name","suiteId":"$className","className":"$className"}"""

  /** A nested suite, whose suite ID is its class name. */
  def nestedSuite(id: Long, parent: Long, position: Int, name: String, className: String): String =
    s"""{"id":$id,"kind":"suite","parent":$parent,"position":$position,"name":"$name","suiteId":"$className","className":"$className"}"""

  def scope(id: Long, parent: Long, position: Int, name: String, label: String): String =
    s"""{"id":$id,"kind":"scope","parent":$parent,"position":$position,"name":"$name","label":"$label"}"""

  def test(id: Long, parent: Long, position: Int, name: String, label: String): String =
    s"""{"id":$id,"kind":"test","parent":$parent,"position":$position,"name":"$name","label":"$label"}"""

  private val fieldsNoCriterionFixes = """"threadName":"any thread","timeStamp":1"""

  /** A ScalaTest event assigned to the run. `fields` holds the event's own fields, each followed by a comma. */
  def runEvent(eventName: String, fields: String = ""): String =
    s"""{"item":"event","event":"$eventName",$fields$fieldsNoCriterionFixes}"""

  /** A ScalaTest event assigned to a suite. `fields` holds the event's own fields, each followed by a comma. */
  def suiteEvent(node: Long, execution: Int, eventName: String, fields: String = ""): String =
    s"""{"item":"event","node":$node,"execution":$execution,"event":"$eventName",$fields$fieldsNoCriterionFixes}"""

  /** A ScalaTest event assigned to a scope or test. `fields` holds the event's own fields, each followed by a comma. */
  def scopeOrTestEvent(node: Long, execution: Int, parentExecution: Int, eventName: String, fields: String = ""): String =
    s"""{"item":"event","node":$node,"execution":$execution,"parentExecution":$parentExecution,"event":"$eventName",$fields$fieldsNoCriterionFixes}"""

  private val nodeReference = """"(id|parent)":(\d+)""".r

  /**
   * Returns the line with each node ID replaced by its place, from 1, among the line's IDs in the order they first
   * appear, so that lines whose nodes are minted from different IDs compare equal.
   */
  def withNodesRenumbered(line: String): String = {
    val placeOfId = mutable.LinkedHashMap.empty[String, Int]
    nodeReference.replaceAllIn(
      line,
      reference => s""""${reference.group(1)}":${placeOfId.getOrElseUpdate(reference.group(2), placeOfId.size + 1)}"""
    )
  }
}
