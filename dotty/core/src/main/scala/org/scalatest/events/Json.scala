package org.scalatest.events

import scala.collection.mutable

private[scalatest] enum JsonValue {
  case Text(value: String)
  case Integer(value: Long)
  case Bool(value: Boolean)
  case Array(items: Vector[JsonValue])
  case Object(fields: Vector[(String, JsonValue)])
}

private[scalatest] object JsonWriter {

  private val hexDigits = "0123456789ABCDEF"

  def write(value: JsonValue): String = {
    val builder = new StringBuilder
    append(builder, value)
    builder.toString
  }

  private def append(builder: StringBuilder, value: JsonValue): Unit = {
    value match {
      case JsonValue.Text(text) => appendString(builder, text)
      case JsonValue.Integer(number) => builder.append(number)
      case JsonValue.Bool(flag) => builder.append(flag)
      case JsonValue.Array(items) =>
        builder.append('[')
        appendSeparated(builder, items)(item => append(builder, item))
        builder.append(']')
      case JsonValue.Object(fields) =>
        builder.append('{')
        appendSeparated(builder, fields) { case (key, fieldValue) =>
          appendString(builder, key)
          builder.append(':')
          append(builder, fieldValue)
        }
        builder.append('}')
    }
  }

  private def appendSeparated[A](builder: StringBuilder, items: Vector[A])(appendItem: A => Unit): Unit = {
    items.zipWithIndex.foreach { case (item, index) =>
      if (index > 0) {
        builder.append(',')
      }
      appendItem(item)
    }
  }

  private def appendString(builder: StringBuilder, text: String): Unit = {
    builder.append('"')
    var index = 0
    while (index < text.length) {
      val char = text.charAt(index)
      val pairedHighSurrogate =
        Character.isHighSurrogate(char) && index + 1 < text.length && Character.isLowSurrogate(text.charAt(index + 1))
      if (pairedHighSurrogate) {
        builder.append(char).append(text.charAt(index + 1))
        index += 2
      } else {
        appendChar(builder, char)
        index += 1
      }
    }
    builder.append('"')
  }

  private def appendChar(builder: StringBuilder, char: Char): Unit = {
    char match {
      case '"' => builder.append("\\\"")
      case '\\' => builder.append("\\\\")
      case '\b' => builder.append("\\b")
      case '\f' => builder.append("\\f")
      case '\n' => builder.append("\\n")
      case '\r' => builder.append("\\r")
      case '\t' => builder.append("\\t")
      case _ =>
        if (char < ' ' || Character.isSurrogate(char)) {
          appendUnicodeEscape(builder, char)
        } else {
          builder.append(char)
        }
    }
  }

  private def appendUnicodeEscape(builder: StringBuilder, char: Char): Unit = {
    builder.append("\\u")
    List(12, 8, 4, 0).foreach(shift => builder.append(hexDigits.charAt((char >> shift) & 0xF)))
  }
}

private[scalatest] object JsonReader {

  val maxDepth: Int = 512

  def read(text: String): Either[String, JsonValue] = {
    val reader = new JsonReader(text)
    for {
      value <- reader.value(0)
      _ <- reader.end()
    } yield value
  }
}

private final class JsonReader(text: String) {

  private var position = 0

  def end(): Either[String, Unit] = {
    skipWhitespace()
    if (position == text.length) {
      Right(())
    } else {
      Left(s"text follows the value at offset $position")
    }
  }

  def value(depth: Int): Either[String, JsonValue] = {
    skipWhitespace()
    if (position >= text.length) {
      Left(s"the text ends where a value should start, at offset $position")
    } else if (depth > JsonReader.maxDepth) {
      Left(s"values are nested more than ${JsonReader.maxDepth} deep")
    } else {
      text.charAt(position) match {
        case '{' => objectValue(depth)
        case '[' => arrayValue(depth)
        case '"' => string().map(JsonValue.Text(_))
        case 't' => literal("true", JsonValue.Bool(true))
        case 'f' => literal("false", JsonValue.Bool(false))
        case char if char == '-' || isDigit(char) => integer()
        case _ => Left(s"no value starts at offset $position")
      }
    }
  }

  private def skipWhitespace(): Unit = {
    while (position < text.length && " \t\n\r".indexOf(text.charAt(position).toInt) >= 0) {
      position += 1
    }
  }

  private def isDigit(char: Char): Boolean = char >= '0' && char <= '9'

  private def at(char: Char): Boolean = position < text.length && text.charAt(position) == char

  private def literal(word: String, value: JsonValue): Either[String, JsonValue] = {
    if (text.startsWith(word, position)) {
      position += word.length
      Right(value)
    } else {
      Left(s"no value starts at offset $position")
    }
  }

  private def integer(): Either[String, JsonValue] = {
    val start = position
    if (at('-')) {
      position += 1
    }
    val firstDigit = position
    while (position < text.length && isDigit(text.charAt(position))) {
      position += 1
    }
    val digitCount = position - firstDigit
    if (digitCount == 0) {
      Left(s"a number has no digits at offset $start")
    } else if (digitCount > 1 && text.charAt(firstDigit) == '0') {
      Left(s"a number has a leading zero at offset $start")
    } else if (position < text.length && ".eE".indexOf(text.charAt(position).toInt) >= 0) {
      Left(s"the number at offset $start is not an integer")
    } else {
      text.substring(start, position).toLongOption
        .map(JsonValue.Integer(_))
        .toRight(s"the integer at offset $start does not fit a Long")
    }
  }

  private def string(): Either[String, String] = {
    val start = position
    position += 1
    val builder = new StringBuilder
    var failure: Option[String] = None
    var closed = false
    while (failure.isEmpty && !closed) {
      if (position >= text.length) {
        failure = Some(s"the string starting at offset $start is not closed")
      } else {
        val char = text.charAt(position)
        position += 1
        if (char == '"') {
          closed = true
        } else if (char == '\\') {
          escapedChar() match {
            case Right(escaped) => builder.append(escaped)
            case Left(reason) => failure = Some(reason)
          }
        } else if (char < ' ') {
          failure = Some(s"an unescaped control character is at offset ${position - 1}")
        } else {
          builder.append(char)
        }
      }
    }
    failure.toLeft(builder.toString)
  }

  private def escapedChar(): Either[String, Char] = {
    if (position >= text.length) {
      Left(s"the text ends inside an escape at offset $position")
    } else {
      val char = text.charAt(position)
      position += 1
      char match {
        case '"' | '\\' | '/' => Right(char)
        case 'b' => Right('\b')
        case 'f' => Right('\f')
        case 'n' => Right('\n')
        case 'r' => Right('\r')
        case 't' => Right('\t')
        case 'u' => unicodeEscapedChar()
        case _ => Left(s"an unknown escape is at offset ${position - 2}")
      }
    }
  }

  private def unicodeEscapedChar(): Either[String, Char] = {
    val digits = text.substring(position, math.min(position + 4, text.length)).map(char => Character.digit(char, 16))
    if (digits.length == 4 && digits.forall(_ >= 0)) {
      position += 4
      Right(digits.foldLeft(0)((codeUnit, digit) => codeUnit * 16 + digit).toChar)
    } else {
      Left(s"a \\u escape lacks four hexadecimal digits at offset $position")
    }
  }

  private def arrayValue(depth: Int): Either[String, JsonValue] = {
    position += 1
    val items = Vector.newBuilder[JsonValue]
    members(']') {
      value(depth + 1).map(item => items += item)
    }.map(_ => JsonValue.Array(items.result()))
  }

  private def objectValue(depth: Int): Either[String, JsonValue] = {
    position += 1
    val fields = Vector.newBuilder[(String, JsonValue)]
    val keys = mutable.Set.empty[String]
    members('}') {
      for {
        key <- key()
        _ <- if (keys.add(key)) Right(()) else Left(s"the key $key is repeated")
        fieldValue <- value(depth + 1)
      } yield fields += (key -> fieldValue)
    }.map(_ => JsonValue.Object(fields.result()))
  }

  private def key(): Either[String, String] = {
    if (!at('"')) {
      Left(s"no key starts at offset $position")
    } else {
      string().flatMap { key =>
        skipWhitespace()
        if (at(':')) {
          position += 1
          Right(key)
        } else {
          Left(s"no ':' follows the key $key")
        }
      }
    }
  }

  private def members(closing: Char)(readMember: => Either[String, Any]): Either[String, Unit] = {
    skipWhitespace()
    if (at(closing)) {
      position += 1
      Right(())
    } else {
      var failure: Option[String] = None
      var closed = false
      while (failure.isEmpty && !closed) {
        skipWhitespace()
        readMember match {
          case Left(reason) => failure = Some(reason)
          case Right(_) =>
            skipWhitespace()
            if (at(',')) {
              position += 1
            } else if (at(closing)) {
              position += 1
              closed = true
            } else {
              failure = Some(s"neither ',' nor '$closing' is at offset $position")
            }
        }
      }
      failure.toLeft(())
    }
  }
}
