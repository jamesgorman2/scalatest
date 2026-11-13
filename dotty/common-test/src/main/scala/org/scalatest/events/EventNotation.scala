package org.scalatest.events

private[events] final class NotationError(message: String) extends RuntimeException(message)

private[events] enum NodeKind(val word: String) {
  case Suite extends NodeKind("suite")
  case Scope extends NodeKind("scope")
  case Test extends NodeKind("test")
}

private[events] final case class NodeReference(kind: NodeKind, name: String, ordinal: Option[Int]) {

  def text: String = kind.word + " \"" + name + "\"" + ordinal.fold("")(" #" + _)
}

private[events] enum WrittenNode {
  case Suite(name: String, className: String, suiteId: Option[String])
  case Scope(name: String, label: String)
  case Test(name: String, label: String, testName: Option[String])
}

private[events] final case class TreeLine(depth: Int, node: WrittenNode, text: String)

private[events] enum FieldValue {
  case Text(value: String)
  case Number(value: Long)
  case Texts(values: Vector[String])
  case Place(location: Location)
  case Thrown(throwable: WrittenThrowable)
}

private[events] enum NotationLine {
  case RunStarted(text: String)
  case Structure(under: Option[NodeReference], tree: Vector[TreeLine], text: String)
  case Event(
    node: Option[NodeReference],
    execution: Option[Int],
    parentExecution: Option[Int],
    className: String,
    fields: Vector[(String, FieldValue)],
    text: String
  )
  case RunEnded(outcome: RunOutcome, text: String)
}

private[scalatest] enum WrittenMessage {
  case Unwritten
  case Null
  case Text(value: String)
}

private[scalatest] final case class WrittenStackDepth(file: Option[String], lineNumber: Option[Int]) {

  def fileName: Option[String] = file.map(written => written.substring(lastSeparator(written) + 1))

  def filePathname: Option[String] = file.filter(written => lastSeparator(written) >= 0)

  private def lastSeparator(written: String): Int = math.max(written.lastIndexOf('/'), written.lastIndexOf('\\'))
}

private[scalatest] final case class WrittenThrowable(
  className: String,
  message: WrittenMessage,
  stackDepth: Option[WrittenStackDepth],
  isPropertyCheckFailure: Boolean,
  frames: Option[Vector[String]],
  suppressed: Option[Vector[WrittenThrowable]],
  cause: Option[WrittenThrowable]
)

private[events] object EventNotation {

  def parse(notation: String): Vector[NotationLine] = {
    val lines = notation.split('\n').toVector.map(_.stripSuffix("\r")).filter(_.trim.nonEmpty).map(indented)
    val outermost = if (lines.isEmpty) 0 else lines.map(_.indentation).min
    val parsed = Vector.newBuilder[NotationLine]
    var remaining = lines
    while (remaining.nonEmpty) {
      val line = remaining.head
      val (under, rest) = remaining.tail.span(_.indentation > outermost)
      if (line.text == "structure" || line.text.startsWith("structure ")) {
        parsed += structure(line, under, outermost)
      } else {
        under.headOption.foreach(stray => throw new NotationError(s"'${stray.text}' is indented under a line that is not a structure"))
        parsed += runOrEvent(line.text)
      }
      remaining = rest
    }
    parsed.result()
  }

  def frame(text: String): DecodedStackFrame = {
    val open = text.lastIndexOf('(')
    val method = if (open < 0) "" else text.substring(text.lastIndexOf('/', open) + 1, open)
    val dot = method.lastIndexOf('.')
    if (dot <= 0 || dot == method.length - 1 || !text.endsWith(")")) {
      throw new NotationError(s"'$text' is not the text StackTraceElement.toString gives a frame")
    }
    val source = text.substring(open + 1, text.length - 1)
    val colon = source.lastIndexOf(':')
    val (fileName, lineNumber) = {
      if (source == "Native Method" || source == "Unknown Source") {
        (None, None)
      } else if (colon < 0) {
        (Some(source), None)
      } else {
        val line = source.substring(colon + 1).toIntOption.getOrElse {
          throw new NotationError(s"'$text' holds a line number that is not a number")
        }
        (Some(source.substring(0, colon)), Some(line))
      }
    }
    DecodedStackFrame(method.substring(0, dot), method.substring(dot + 1), fileName, lineNumber, source == "Native Method", text)
  }

  private final case class IndentedLine(indentation: Int, text: String)

  private def indented(line: String): IndentedLine = IndentedLine(line.takeWhile(_ == ' ').length, line.trim)

  private def structure(header: IndentedLine, under: Vector[IndentedLine], outermost: Int): NotationLine = {
    val cursor = new LineCursor(header.text)
    cursor.expect("structure")
    val parent = if (cursor.accepts(" under ")) Some(nodeReference(cursor)) else None
    cursor.end()
    parent.filter(_.kind != NodeKind.Suite).foreach(_ => cursor.fail("a structure is under a suite"))
    val tree = under.map { line =>
      val depth = line.indentation - outermost
      if (depth % 2 != 0) {
        throw new NotationError(s"'${line.text}' is not indented by a multiple of 2 spaces under its structure")
      }
      TreeLine(depth / 2, writtenNode(line.text), line.text)
    }
    val text = (header.text +: under.map(line => " " * (line.indentation - outermost) + line.text)).mkString("\n")
    NotationLine.Structure(parent, tree, text)
  }

  private def writtenNode(text: String): WrittenNode = {
    val cursor = new LineCursor(text)
    val node = {
      if (cursor.accepts("suite ")) {
        val name = cursor.quoted()
        cursor.expect(", class ")
        val className = cursor.quoted()
        WrittenNode.Suite(name, className, if (cursor.accepts(", id ")) Some(cursor.quoted()) else None)
      } else if (cursor.accepts("scope ")) {
        val name = cursor.quoted()
        cursor.expect(", label ")
        WrittenNode.Scope(name, cursor.quoted())
      } else if (cursor.accepts("test ")) {
        val name = cursor.quoted()
        cursor.expect(", label ")
        val label = cursor.quoted()
        WrittenNode.Test(name, label, if (cursor.accepts(", test name ")) Some(cursor.quoted()) else None)
      } else {
        cursor.fail("expected suite, scope or test")
      }
    }
    cursor.end()
    node
  }

  private def runOrEvent(text: String): NotationLine = {
    if (text == "run started") {
      NotationLine.RunStarted(text)
    } else if (text.startsWith("run ended ")) {
      NotationLine.RunEnded(outcome(text.stripPrefix("run ended ")), text)
    } else {
      event(text)
    }
  }

  private def outcome(how: String): RunOutcome = {
    how match {
      case "completed" => RunOutcome.Completed
      case "aborted" => RunOutcome.Aborted
      case "stopped" => RunOutcome.Stopped
      case other => throw new NotationError(s"a run ends completed, aborted or stopped, not '$other'")
    }
  }

  private def event(text: String): NotationLine = {
    val cursor = new LineCursor(text)
    val node = if (cursor.accepts("run")) None else Some(nodeReference(cursor))
    val execution = if (cursor.accepts(" execution ")) Some(cursor.count()) else None
    val parentExecution = if (cursor.accepts(", parent execution ")) Some(cursor.count()) else None
    cursor.expect(": ")
    val className = cursor.word("an event class")(_.isLetterOrDigit)
    val fields = if (cursor.accepts("(")) eventFields(cursor) else Vector.empty
    cursor.end()
    NotationLine.Event(node, execution, parentExecution, className, fields, text)
  }

  private def nodeReference(cursor: LineCursor): NodeReference = {
    val kind = NodeKind.values.find(kind => cursor.accepts(kind.word + " ")).getOrElse(cursor.fail("expected run, suite, scope or test"))
    val name = cursor.quoted()
    NodeReference(kind, name, if (cursor.accepts(" #")) Some(cursor.count()) else None)
  }

  private def eventFields(cursor: LineCursor): Vector[(String, FieldValue)] = {
    val fields = Vector.newBuilder[(String, FieldValue)]
    var closed = false
    while (!closed) {
      val name = cursor.word("a field name")(_.isLetter)
      cursor.expect(": ")
      fields += name -> fieldValue(name, cursor)
      if (!cursor.accepts(", ")) {
        cursor.expect(")")
        closed = true
      }
    }
    fields.result()
  }

  private def fieldValue(name: String, cursor: LineCursor): FieldValue = {
    name match {
      case "message" | "text" | "threadName" => FieldValue.Text(cursor.quoted())
      case "analysis" =>
        cursor.expect("[")
        FieldValue.Texts(list(cursor)(_.quoted()))
      case "duration" | "timeStamp" | "testCount" => FieldValue.Number(cursor.number())
      case "location" => FieldValue.Place(location(cursor))
      case "throwable" => FieldValue.Thrown(throwable(cursor))
      case other => cursor.fail(s"the notation writes no field '$other'")
    }
  }

  private def list[A](cursor: LineCursor)(element: LineCursor => A): Vector[A] = {
    val elements = Vector.newBuilder[A]
    var closed = cursor.accepts("]")
    while (!closed) {
      elements += element(cursor)
      if (!cursor.accepts(", ")) {
        cursor.expect("]")
        closed = true
      }
    }
    elements.result()
  }

  private def location(cursor: LineCursor): Location = {
    if (cursor.accepts("SeeStackDepthException")) {
      SeeStackDepthException
    } else if (cursor.accepts("TopOfClass(")) {
      val className = cursor.quoted()
      cursor.expect(")")
      TopOfClass(className)
    } else if (cursor.accepts("TopOfMethod(")) {
      val className = cursor.quoted()
      cursor.expect(", ")
      val methodId = cursor.quoted()
      cursor.expect(")")
      TopOfMethod(className, methodId)
    } else if (cursor.accepts("LineInFile(")) {
      val lineNumber = cursor.int()
      cursor.expect(", ")
      val fileName = cursor.quoted()
      cursor.expect(", ")
      val filePathname = {
        if (cursor.accepts("None")) {
          None
        } else {
          cursor.expect("Some(")
          val pathname = cursor.quoted()
          cursor.expect(")")
          Some(pathname)
        }
      }
      cursor.expect(")")
      LineInFile(lineNumber, fileName, filePathname)
    } else {
      cursor.fail("expected SeeStackDepthException, TopOfClass, TopOfMethod or LineInFile")
    }
  }

  // Recurses once for each cause and each nested list of suppressed throwables a test writes on one line.
  private def throwable(cursor: LineCursor): WrittenThrowable = {
    val className = cursor.word("a throwable's class name")(part => part.isLetterOrDigit || part == '.' || part == '$' || part == '_')
    val message = {
      if (cursor.accepts(" null")) {
        WrittenMessage.Null
      } else if (cursor.accepts(" \"")) {
        WrittenMessage.Text(cursor.quotedFromOpened())
      } else {
        WrittenMessage.Unwritten
      }
    }
    val stackDepth = if (cursor.accepts(" at ")) Some(writtenStackDepth(cursor)) else None
    val isPropertyCheckFailure = cursor.accepts(" property check")
    val frames = if (cursor.accepts(" frames [")) Some(list(cursor)(_.quoted())) else None
    val suppressed = if (cursor.accepts(" suppressing [")) Some(list(cursor)(throwable)) else None
    val cause = if (cursor.accepts(" caused by ")) Some(throwable(cursor)) else None
    WrittenThrowable(className, message, stackDepth, isPropertyCheckFailure, frames, suppressed, cause)
  }

  private def writtenStackDepth(cursor: LineCursor): WrittenStackDepth = {
    val failedCode = cursor.word("<file name>:<line number>")(part => !part.isWhitespace && part != ',' && part != ')' && part != ']')
    val colon = failedCode.lastIndexOf(':')
    if (colon <= 0) {
      cursor.fail(s"'$failedCode' is not <file name>:<line number>")
    }
    val file = Some(failedCode.substring(0, colon)).filter(_ != "?")
    val lineNumber = failedCode.substring(colon + 1) match {
      case "?" => None
      case digits => Some(digits.toIntOption.getOrElse(cursor.fail(s"'$digits' is not a line number")))
    }
    WrittenStackDepth(file, lineNumber)
  }
}

private final class LineCursor(line: String) {

  private var position = 0

  def fail(reason: String): Nothing = throw new NotationError(s"$reason at column ${position + 1} of '$line'")

  def accepts(literal: String): Boolean = {
    val found = line.startsWith(literal, position)
    if (found) {
      position += literal.length
    }
    found
  }

  def expect(literal: String): Unit = {
    if (!accepts(literal)) {
      fail(s"expected '$literal'")
    }
  }

  def end(): Unit = {
    if (position < line.length) {
      fail("unexpected text")
    }
  }

  def word(expected: String)(isPart: Char => Boolean): String = {
    val start = position
    while (position < line.length && isPart(line.charAt(position))) {
      position += 1
    }
    if (position == start) {
      fail(s"expected $expected")
    }
    line.substring(start, position)
  }

  def number(): Long = {
    val start = position
    val digits = (if (accepts("-")) "-" else "") + word("a number")(_.isDigit)
    digits.toLongOption.getOrElse {
      position = start
      fail(s"'$digits' does not fit a Long")
    }
  }

  def int(): Int = {
    val start = position
    val value = number()
    if (value < Int.MinValue || value > Int.MaxValue) {
      position = start
      fail(s"'$value' does not fit an Int")
    }
    value.toInt
  }

  def count(): Int = {
    val start = position
    val value = int()
    if (value < 1) {
      position = start
      fail("expected a number from 1")
    }
    value
  }

  def quoted(): String = {
    expect("\"")
    quotedFromOpened()
  }

  def quotedFromOpened(): String = {
    val text = new StringBuilder
    var closed = false
    while (!closed) {
      next("a string that is not closed") match {
        case '"' => closed = true
        case '\\' => text.append(escaped())
        case other => text.append(other)
      }
    }
    text.toString
  }

  private def next(unfinished: String): Char = {
    if (position >= line.length) {
      fail(unfinished)
    }
    position += 1
    line.charAt(position - 1)
  }

  private def escaped(): Char = {
    next("an escape that is not finished") match {
      case 'n' => '\n'
      case 'r' => '\r'
      case 't' => '\t'
      case '"' => '"'
      case '\\' => '\\'
      case 'u' =>
        val digits = line.substring(position, math.min(position + 4, line.length))
        if (digits.length < 4 || !digits.forall(digit => Character.digit(digit, 16) >= 0)) {
          fail("expected 4 hexadecimal digits")
        }
        position += 4
        Integer.parseInt(digits, 16).toChar
      case other =>
        position -= 1
        fail(s"the notation has no escape '\\$other'")
    }
  }
}
