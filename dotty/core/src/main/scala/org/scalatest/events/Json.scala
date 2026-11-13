package org.scalatest.events

import scala.annotation.tailrec
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

  private enum Pending {
    case Value(value: JsonValue)
    case Key(key: String)
    case Punctuation(char: Char)
  }

  // Keeps what is still to write in a list rather than on the call stack, so that no depth of nesting overflows it.
  def write(value: JsonValue): String = {
    val builder = new StringBuilder
    var pending = List[Pending](Pending.Value(value))
    while (pending.nonEmpty) {
      val next = pending.head
      pending = pending.tail
      next match {
        case Pending.Punctuation(char) => builder.append(char)
        case Pending.Key(key) =>
          appendString(builder, key)
          builder.append(':')
        case Pending.Value(JsonValue.Text(text)) => appendString(builder, text)
        case Pending.Value(JsonValue.Integer(number)) => builder.append(number)
        case Pending.Value(JsonValue.Bool(flag)) => builder.append(flag)
        case Pending.Value(JsonValue.Array(items)) =>
          builder.append('[')
          val members = items.map(item => List[Pending](Pending.Value(item)))
          pending = separated(members) ::: Pending.Punctuation(']') :: pending
        case Pending.Value(JsonValue.Object(fields)) =>
          builder.append('{')
          val members = fields.map { case (key, fieldValue) => List[Pending](Pending.Key(key), Pending.Value(fieldValue)) }
          pending = separated(members) ::: Pending.Punctuation('}') :: pending
      }
    }
    builder.toString
  }

  private def separated(members: Vector[List[Pending]]): List[Pending] = {
    members.zipWithIndex.toList.flatMap { case (member, index) =>
      if (index > 0) Pending.Punctuation(',') :: member else member
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

  def read(text: String): Either[String, JsonValue] = {
    val reader = new JsonReader(text)
    for {
      value <- reader.value()
      _ <- reader.end()
    } yield value
  }

  private sealed abstract class OpenContainer(val closing: Char) {

    def add(value: JsonValue): Unit

    def closed: JsonValue
  }

  private final class OpenArray extends OpenContainer(']') {

    private val items = Vector.newBuilder[JsonValue]

    def add(value: JsonValue): Unit = items += value

    def closed: JsonValue = JsonValue.Array(items.result())
  }

  private final class OpenObject extends OpenContainer('}') {

    private val fields = Vector.newBuilder[(String, JsonValue)]
    private val keys = mutable.Set.empty[String]
    private var keyOfNextValue = ""

    def startField(key: String): Either[String, Unit] = {
      keyOfNextValue = key
      if (keys.add(key)) Right(()) else Left(s"the key $key is repeated")
    }

    def add(value: JsonValue): Unit = fields += (keyOfNextValue -> value)

    def closed: JsonValue = JsonValue.Object(fields.result())
  }
}

private final class JsonReader(text: String) {

  import JsonReader.{OpenArray, OpenContainer, OpenObject}

  private var position = 0
  // Innermost first. Held here rather than on the call stack, so that no depth of nesting overflows it.
  private var open = List.empty[OpenContainer]

  def end(): Either[String, Unit] = {
    skipWhitespace()
    if (position == text.length) {
      Right(())
    } else {
      Left(s"text follows the value at offset $position")
    }
  }

  def value(): Either[String, JsonValue] = placed(valueStart())

  // A step is the value just read, or None where it opened a container.
  @tailrec
  private def placed(step: Either[String, Option[JsonValue]]): Either[String, JsonValue] = {
    step match {
      case Left(reason) => Left(reason)
      case Right(None) => placed(firstMember())
      case Right(Some(value)) =>
        open match {
          case Nil => Right(value)
          case innermost :: _ =>
            innermost.add(value)
            placed(nextMember(innermost))
        }
    }
  }

  private def valueStart(): Either[String, Option[JsonValue]] = {
    skipWhitespace()
    if (position >= text.length) {
      Left(s"the text ends where a value should start, at offset $position")
    } else {
      text.charAt(position) match {
        case '{' => opened(new OpenObject)
        case '[' => opened(new OpenArray)
        case '"' => string().map(read => Some(JsonValue.Text(read)))
        case 't' => literal("true", JsonValue.Bool(true))
        case 'f' => literal("false", JsonValue.Bool(false))
        case char if char == '-' || isDigit(char) => integer()
        case _ => Left(s"no value starts at offset $position")
      }
    }
  }

  private def opened(container: OpenContainer): Either[String, Option[JsonValue]] = {
    position += 1
    open = container :: open
    Right(None)
  }

  private def firstMember(): Either[String, Option[JsonValue]] = {
    val innermost = open.head
    skipWhitespace()
    if (at(innermost.closing)) close(innermost) else member(innermost)
  }

  private def nextMember(innermost: OpenContainer): Either[String, Option[JsonValue]] = {
    skipWhitespace()
    if (at(',')) {
      position += 1
      member(innermost)
    } else if (at(innermost.closing)) {
      close(innermost)
    } else {
      Left(s"neither ',' nor '${innermost.closing}' is at offset $position")
    }
  }

  private def member(innermost: OpenContainer): Either[String, Option[JsonValue]] = {
    skipWhitespace()
    innermost match {
      case _: OpenArray => valueStart()
      case openObject: OpenObject => key().flatMap(openObject.startField).flatMap(_ => valueStart())
    }
  }

  private def close(innermost: OpenContainer): Either[String, Option[JsonValue]] = {
    position += 1
    open = open.tail
    Right(Some(innermost.closed))
  }

  private def skipWhitespace(): Unit = {
    while (position < text.length && " \t\n\r".indexOf(text.charAt(position).toInt) >= 0) {
      position += 1
    }
  }

  private def isDigit(char: Char): Boolean = char >= '0' && char <= '9'

  private def at(char: Char): Boolean = position < text.length && text.charAt(position) == char

  private def literal(word: String, value: JsonValue): Either[String, Option[JsonValue]] = {
    if (text.startsWith(word, position)) {
      position += word.length
      Right(Some(value))
    } else {
      Left(s"no value starts at offset $position")
    }
  }

  private def integer(): Either[String, Option[JsonValue]] = {
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
        .map(number => Some(JsonValue.Integer(number)))
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
    val digits = text.substring(position, math.min(position + 4, text.length)).map(hexDigit)
    if (digits.length == 4 && digits.forall(_ >= 0)) {
      position += 4
      Right(digits.foldLeft(0)((codeUnit, digit) => codeUnit * 16 + digit).toChar)
    } else {
      Left(s"a \\u escape lacks four hexadecimal digits at offset $position")
    }
  }

  // Character.digit also takes the digits of other scripts, which JSON does not.
  private def hexDigit(char: Char): Int = {
    if (char >= '0' && char <= '9') {
      char - '0'
    } else if (char >= 'a' && char <= 'f') {
      char - 'a' + 10
    } else if (char >= 'A' && char <= 'F') {
      char - 'A' + 10
    } else {
      -1
    }
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
}
