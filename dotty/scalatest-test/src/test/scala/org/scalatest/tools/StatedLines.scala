package org.scalatest.tools

import org.scalatest.events.JsonReader
import org.scalatest.events.JsonValue

/**
 * Compares the lines a run wrote to its event log with the lines a criterion states, on the keys the stated lines
 * hold. A written line may hold keys its stated line lacks, such as a duration or a location.
 */
private[tools] object StatedLines {

  // A stated line holds these only so that it decodes. No criterion fixes their values, so they are not compared.
  private val keysNoCriterionFixes = Set("threadName", "timeStamp", "pid", "startTime")

  /** Returns why the written lines are not the stated ones, one for one, or none where they are. */
  def mismatchWhenIs(written: Seq[String], stated: Seq[String]): Option[String] = {
    if (written.size != stated.size) {
      Some(s"the event log has ${written.size} lines where ${stated.size} are stated:\n${written.mkString("\n")}")
    } else {
      written.zip(stated).collectFirst {
        case (writtenLine, statedLine) if !holds(writtenLine, statedLine) => s"the event log has\n$writtenLine\nwhere this is stated:\n$statedLine"
      }
    }
  }

  /**
   * Matches each stated line, in order, against the written lines after the one the stated line before it matched.
   * Returns why the first stated line with no match is missing, or none where every stated line matches.
   */
  def mismatchWhenHoldsInOrder(written: Seq[String], stated: Seq[String]): Option[String] = {
    val (_, missing) = stated.foldLeft((written, Option.empty[String])) {
      case ((unmatched, None), statedLine) =>
        val fromItsMatch = unmatched.dropWhile(writtenLine => !holds(writtenLine, statedLine))
        if (fromItsMatch.isEmpty) (fromItsMatch, Some(statedLine)) else (fromItsMatch.drop(1), None)
      case (missingAlready, _) => missingAlready
    }
    missing.map(statedLine => s"the event log does not hold, after the stated lines before it:\n$statedLine\nIt is:\n${written.mkString("\n")}")
  }

  private def holds(writtenLine: String, statedLine: String): Boolean = holds(parsed(writtenLine), parsed(statedLine))

  private def parsed(line: String): JsonValue =
    JsonReader.read(line).fold(reason => throw new IllegalArgumentException(s"$reason, in $line"), identity)

  // Recurses once for each object or array nested in another, which in an event log's line is at most a throwable's parts.
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
