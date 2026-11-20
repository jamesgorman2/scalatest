package org.scalatest.mill

final class JsonPattern private (pattern: ujson.Value) {

  def mismatches(actual: ujson.Value): Seq[String] = JsonPattern.mismatches("$", pattern, actual)

  def mismatchesHeldBy(actual: ujson.Value): Seq[String] = {
    val keys = pattern.obj.keys.toSeq
    actual.objOpt match {
      case None => Seq(s"$$: expected an object holding ${keys.mkString(", ")}, found $actual")
      case Some(fields) => {
        val missing = keys.filterNot(fields.contains).map(key => s"$$: no key $key in $actual")
        val heldInOrder = fields.keys.toSeq.filter(keys.contains)
        val order = if (missing.isEmpty && heldInOrder != keys) Seq(s"$$: keys ${heldInOrder.mkString(", ")} are not in the order ${keys.mkString(", ")}") else Nil
        val values = keys.filter(fields.contains).flatMap(key => JsonPattern.mismatches(s"$$.$key", pattern(key), fields(key)))
        missing ++ order ++ values
      }
    }
  }
}

object JsonPattern {

  private val placeholderMark = '\u0000'
  private val placeholder = "<([a-z][^<>\"]*)>".r
  private val lineOf = "line ([0-9]+) of (.+)".r
  private val classIn = "class (\\S+) in (.+)".r

  def parse(text: String): JsonPattern =
    new JsonPattern(ujson.read(placeholder.replaceAllIn(text, found => s""""\\\\u0000${found.group(1)}"""")))

  private def mismatches(path: String, pattern: ujson.Value, actual: ujson.Value): Seq[String] =
    pattern match {
      case ujson.Str(text) if text.headOption.contains(placeholderMark) => placeholderMismatches(path, text.tail, actual)
      case ujson.Obj(fields) =>
        actual.objOpt match {
          case Some(actualFields) if actualFields.keys.toSeq == fields.keys.toSeq =>
            fields.toSeq.flatMap { case (key, value) => mismatches(s"$path.$key", value, actualFields(key)) }
          case _ => Seq(s"$path: expected an object with keys ${fields.keys.mkString(", ")}, found $actual")
        }
      case ujson.Arr(items) =>
        actual.arrOpt match {
          case Some(actualItems) if actualItems.size == items.size =>
            items.indices.flatMap(index => mismatches(s"$path[$index]", items(index), actualItems(index)))
          case _ => Seq(s"$path: expected an array of ${items.size} items, found $actual")
        }
      case _ => if (pattern == actual) Nil else Seq(s"$path: expected $pattern, found $actual")
    }

  private def placeholderMismatches(path: String, name: String, actual: ujson.Value): Seq[String] = {
    val matches = name match {
      case "time" => isInteger(actual)
      case "ms" => isInteger(actual) && actual.num >= 0
      case "thread" | "string" => actual.strOpt.isDefined
      case "frames" => actual.arrOpt.exists(frames => frames.nonEmpty && frames.forall(isStackFrame))
      case lineOf(lineNumber, file) => isLocation(actual, "lineNumber" -> ujson.Num(lineNumber.toDouble), file)
      case classIn(className, file) => isLocation(actual, "className" -> ujson.Str(className), file)
      case _ => throw new IllegalArgumentException(s"No placeholder <$name>")
    }
    if (matches) Nil else Seq(s"$path: expected <$name>, found $actual")
  }

  private def isInteger(value: ujson.Value): Boolean = value.numOpt.exists(number => number.isWhole)

  private def isStackFrame(frame: ujson.Value): Boolean =
    frame.objOpt.exists(fields => fields.get("className").exists(_.strOpt.isDefined) && fields.get("methodName").exists(_.strOpt.isDefined))

  private def isLocation(actual: ujson.Value, field: (String, ujson.Value), file: String): Boolean =
    actual.objOpt.exists { fields =>
      val (key, value) = field
      val namesFile = (fields.get("filePath"), fields.get("fileName")) match {
        case (Some(ujson.Str(path)), None) => path.split("[/\\\\]").lastOption.contains(file)
        case (None, Some(ujson.Str(name))) => name == file
        case _ => false
      }
      fields.size == 2 && fields.get(key).contains(value) && namesFile
    }
}
