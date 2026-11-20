package org.scalatest.tools

import org.scalatest.events.JsonReader
import org.scalatest.events.JsonValue

/**
 * Compares on the keys the stated lines hold: a written line may hold keys its stated line lacks, such as a duration
 * or a location.
 */
private[tools] object StatedLines {

  // A stated line holds these only so that it decodes. No test checks their values, so they are not compared.
  private val keysNoCriterionFixes = Set("threadName", "timeStamp", "pid", "startTime")

  def mismatchWhenIs(written: Seq[String], stated: Seq[String]): Option[String] = {
    if (written.size != stated.size) {
      Some(s"the event log has ${written.size} lines where ${stated.size} are stated:\n${written.mkString("\n")}")
    } else {
      written.zip(stated).collectFirst {
        case (writtenLine, statedLine) if !holds(writtenLine, statedLine) => s"the event log has\n$writtenLine\nwhere this is stated:\n$statedLine"
      }
    }
  }

  /** Each stated line must match a written line after the one the stated line before it matched. */
  def mismatchWhenHoldsInOrder(written: Seq[String], stated: Seq[String]): Option[String] = {
    val (_, missing) = stated.foldLeft((written, Option.empty[String])) {
      case ((unmatched, None), statedLine) =>
        val fromItsMatch = unmatched.dropWhile(writtenLine => !holds(writtenLine, statedLine))
        if (fromItsMatch.isEmpty) (fromItsMatch, Some(statedLine)) else (fromItsMatch.drop(1), None)
      case (missingAlready, _) => missingAlready
    }
    missing.map(statedLine => s"the event log does not hold, after the stated lines before it:\n$statedLine\nIt is:\n${written.mkString("\n")}")
  }

  private def holds(writtenLine: String, statedLine: String): Boolean =
    holds(JsonReader.read(writtenLine), JsonReader.read(statedLine))

  // The recursion is shallow: in an event log line, only a node path's places and a throwable's parts nest.
  private def holds(written: JsonValue, stated: JsonValue): Boolean = {
    (written, stated) match {
      case (JsonValue.Object(writtenFields), JsonValue.Object(statedFields)) =>
        statedFields.forall { (key, statedValue) =>
          keysNoCriterionFixes.contains(key) ||
          writtenFields.collectFirst { case (`key`, writtenValue) => writtenValue }.exists(holds(_, statedValue))
        }
      case (JsonValue.Array(writtenItems), JsonValue.Array(statedItems)) =>
        writtenItems.size == statedItems.size && writtenItems.zip(statedItems).forall((writtenItem, statedItem) => holds(writtenItem, statedItem))
      case _ => written == stated
    }
  }
}
