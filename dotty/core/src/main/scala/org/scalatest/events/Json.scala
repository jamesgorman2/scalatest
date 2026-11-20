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

  def write(value: JsonValue): String = {
    val builder = new java.lang.StringBuilder
    write(value, builder)
    builder.toString
  }

  /**
   * Appends the value's text to `out` piece by piece, without building the whole text itself. Throws what `out` throws.
   */
  // Keeps what is still to write in a list, not on the call stack, so that deep nesting cannot overflow the stack.
  def write(value: JsonValue, out: Appendable): Unit = {
    var pending = List[Pending](Pending.Value(value))
    while (pending.nonEmpty) {
      val next = pending.head
      pending = pending.tail
      next match {
        case Pending.Punctuation(char) => out.append(char)
        case Pending.Key(key) =>
          appendString(out, key)
          out.append(':')
        case Pending.Value(JsonValue.Text(text)) => appendString(out, text)
        case Pending.Value(JsonValue.Integer(number)) => out.append(number.toString)
        case Pending.Value(JsonValue.Bool(flag)) => out.append(flag.toString)
        case Pending.Value(JsonValue.Array(items)) =>
          out.append('[')
          val members = items.map(item => List[Pending](Pending.Value(item)))
          pending = separated(members) ::: Pending.Punctuation(']') :: pending
        case Pending.Value(JsonValue.Object(fields)) =>
          out.append('{')
          val members = fields.map { case (key, fieldValue) => List[Pending](Pending.Key(key), Pending.Value(fieldValue)) }
          pending = separated(members) ::: Pending.Punctuation('}') :: pending
      }
    }
  }

  private def separated(members: Vector[List[Pending]]): List[Pending] = {
    members.zipWithIndex.toList.flatMap { case (member, index) =>
      if (index > 0) Pending.Punctuation(',') :: member else member
    }
  }

  private def appendString(out: Appendable, text: String): Unit = {
    out.append('"')
    var index = 0
    while (index < text.length) {
      val char = text.charAt(index)
      val pairedHighSurrogate =
        Character.isHighSurrogate(char) && index + 1 < text.length && Character.isLowSurrogate(text.charAt(index + 1))
      if (pairedHighSurrogate) {
        out.append(char).append(text.charAt(index + 1))
        index += 2
      } else {
        appendChar(out, char)
        index += 1
      }
    }
    out.append('"')
  }

  private def appendChar(out: Appendable, char: Char): Unit = {
    char match {
      case '"' => out.append("\\\"")
      case '\\' => out.append("\\\\")
      case '\b' => out.append("\\b")
      case '\f' => out.append("\\f")
      case '\n' => out.append("\\n")
      case '\r' => out.append("\\r")
      case '\t' => out.append("\\t")
      case _ =>
        if (char < ' ' || Character.isSurrogate(char)) {
          appendUnicodeEscape(out, char)
        } else {
          out.append(char)
        }
    }
  }

  private def appendUnicodeEscape(out: Appendable, char: Char): Unit = {
    out.append("\\u")
    List(12, 8, 4, 0).foreach(shift => out.append(hexDigits.charAt((char >> shift) & 0xF)))
  }
}

private[scalatest] object JsonReader {

  /** @throws MalformedJsonException if the text is not exactly one JSON value */
  def read(text: String): JsonValue = {
    val reader = new JsonReader(text)
    val value = reader.value()
    reader.end()
    value
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

    def holds(key: String): Boolean = keys.contains(key)

    def startField(key: String): Unit = {
      keyOfNextValue = key
      keys.add(key)
    }

    def add(value: JsonValue): Unit = fields += (keyOfNextValue -> value)

    def closed: JsonValue = JsonValue.Object(fields.result())
  }
}

private final class JsonReader(text: String) {

  import JsonReader.{OpenArray, OpenContainer, OpenObject}

  private var position = 0
  // Innermost first. Held here, not on the call stack, so that deep nesting cannot overflow the stack.
  private var open = List.empty[OpenContainer]

  def end(): Unit = {
    skipWhitespace()
    if (position != text.length) {
      malformed(s"text follows the value at offset $position")
    }
  }

  def value(): JsonValue = placed(valueStart())

  // A step is the value just read, or None where it opened a container.
  @tailrec
  private def placed(step: Option[JsonValue]): JsonValue = {
    step match {
      case None => placed(firstMember())
      case Some(value) =>
        open match {
          case Nil => value
          case innermost :: _ =>
            innermost.add(value)
            placed(nextMember(innermost))
        }
    }
  }

  private def malformed(reason: String): Nothing = throw new MalformedJsonException(text, reason)

  private def valueStart(): Option[JsonValue] = {
    skipWhitespace()
    if (position >= text.length) {
      malformed(s"the text ends where a value should start, at offset $position")
    } else {
      text.charAt(position) match {
        case '{' => opened(new OpenObject)
        case '[' => opened(new OpenArray)
        case '"' => Some(JsonValue.Text(string()))
        case 't' => literal("true", JsonValue.Bool(true))
        case 'f' => literal("false", JsonValue.Bool(false))
        case char if char == '-' || isDigit(char) => Some(integer())
        case _ => malformed(s"no value starts at offset $position")
      }
    }
  }

  private def opened(container: OpenContainer): Option[JsonValue] = {
    position += 1
    open = container :: open
    None
  }

  private def firstMember(): Option[JsonValue] = {
    val innermost = open.head
    skipWhitespace()
    if (at(innermost.closing)) close(innermost) else member(innermost)
  }

  private def nextMember(innermost: OpenContainer): Option[JsonValue] = {
    skipWhitespace()
    if (at(',')) {
      position += 1
      member(innermost)
    } else if (at(innermost.closing)) {
      close(innermost)
    } else {
      malformed(s"neither ',' nor '${innermost.closing}' is at offset $position")
    }
  }

  private def member(innermost: OpenContainer): Option[JsonValue] = {
    skipWhitespace()
    innermost match {
      case _: OpenArray => valueStart()
      case openObject: OpenObject =>
        val fieldKey = key()
        if (openObject.holds(fieldKey)) {
          malformed(s"the key $fieldKey is repeated")
        }
        openObject.startField(fieldKey)
        valueStart()
    }
  }

  private def close(innermost: OpenContainer): Option[JsonValue] = {
    position += 1
    open = open.tail
    Some(innermost.closed)
  }

  private def skipWhitespace(): Unit = {
    while (position < text.length && " \t\n\r".indexOf(text.charAt(position).toInt) >= 0) {
      position += 1
    }
  }

  private def isDigit(char: Char): Boolean = char >= '0' && char <= '9'

  private def at(char: Char): Boolean = position < text.length && text.charAt(position) == char

  private def literal(word: String, value: JsonValue): Option[JsonValue] = {
    if (text.startsWith(word, position)) {
      position += word.length
      Some(value)
    } else {
      malformed(s"no value starts at offset $position")
    }
  }

  private def integer(): JsonValue = {
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
      malformed(s"a number has no digits at offset $start")
    } else if (digitCount > 1 && text.charAt(firstDigit) == '0') {
      malformed(s"a number has a leading zero at offset $start")
    } else if (position < text.length && ".eE".indexOf(text.charAt(position).toInt) >= 0) {
      malformed(s"the number at offset $start is not an integer")
    } else {
      val digits = text.substring(start, position)
      JsonValue.Integer(digits.toLongOption.getOrElse(malformed(s"the integer at offset $start does not fit a Long")))
    }
  }

  private def string(): String = {
    val start = position
    position += 1
    val builder = new StringBuilder
    var closed = false
    while (!closed) {
      if (position >= text.length) {
        malformed(s"the string starting at offset $start is not closed")
      }
      val char = text.charAt(position)
      position += 1
      if (char == '"') {
        closed = true
      } else if (char == '\\') {
        builder.append(escapedChar())
      } else if (char < ' ') {
        malformed(s"an unescaped control character is at offset ${position - 1}")
      } else {
        builder.append(char)
      }
    }
    builder.toString
  }

  private def escapedChar(): Char = {
    if (position >= text.length) {
      malformed(s"the text ends inside an escape at offset $position")
    }
    val char = text.charAt(position)
    position += 1
    char match {
      case '"' | '\\' | '/' => char
      case 'b' => '\b'
      case 'f' => '\f'
      case 'n' => '\n'
      case 'r' => '\r'
      case 't' => '\t'
      case 'u' => unicodeEscapedChar()
      case _ => malformed(s"an unknown escape is at offset ${position - 2}")
    }
  }

  private def unicodeEscapedChar(): Char = {
    val digits = text.substring(position, math.min(position + 4, text.length)).map(hexDigit)
    if (digits.length != 4 || digits.exists(_ < 0)) {
      malformed(s"a \\u escape lacks four hexadecimal digits at offset $position")
    }
    position += 4
    digits.foldLeft(0)((codeUnit, digit) => codeUnit * 16 + digit).toChar
  }

  // Character.digit also accepts the digits of other scripts, which JSON does not.
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

  private def key(): String = {
    if (!at('"')) {
      malformed(s"no key starts at offset $position")
    }
    val fieldKey = string()
    skipWhitespace()
    if (!at(':')) {
      malformed(s"no ':' follows the key $fieldKey")
    }
    position += 1
    fieldKey
  }
}
