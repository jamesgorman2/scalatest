package org.scalatest.tools

import org.scalatest.events.JsonReader
import org.scalatest.events.JsonValue

private[tools] object StatedLines {

  private val keysStatedOnlyToDecode = Set("threadName", "timeStamp", "pid", "startTime")

  def mismatchWhenIs(written: Seq[String], stated: Seq[String]): Option[String] = {
    if (written.size != stated.size) {
      Some(s"the event log has ${written.size} lines where ${stated.size} are stated:\n${written.mkString("\n")}")
    } else {
      written.zip(stated).collectFirst {
        case (writtenLine, statedLine) if !holds(writtenLine, statedLine) => s"the event log has\n$writtenLine\nwhere this is stated:\n$statedLine"
      }
    }
  }

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

  private def holds(written: JsonValue, stated: JsonValue): Boolean = {
    (written, stated) match {
      case (JsonValue.Object(writtenFields), JsonValue.Object(statedFields)) =>
        statedFields.forall { (key, statedValue) =>
          keysStatedOnlyToDecode.contains(key) ||
          writtenFields.collectFirst { case (`key`, writtenValue) => writtenValue }.exists(holds(_, statedValue))
        }
      case (JsonValue.Array(writtenItems), JsonValue.Array(statedItems)) =>
        writtenItems.size == statedItems.size && writtenItems.zip(statedItems).forall((writtenItem, statedItem) => holds(writtenItem, statedItem))
      case _ => written == stated
    }
  }
}
