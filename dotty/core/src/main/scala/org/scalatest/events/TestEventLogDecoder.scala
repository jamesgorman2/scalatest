package org.scalatest.events

import scala.collection.mutable

private[scalatest] object TestEventLogDecoder {

  def decode(line: String): TestEventLogEvent = {
    jsonValue(line) match {
      case JsonValue.Object(fields) => whole(new JsonObjectReader(line, fields))(logEvent)
      case _ => throw new MalformedTestEventLogException(line, "the line is not a JSON object")
    }
  }

  private def jsonValue(line: String): JsonValue =
    try {
      JsonReader.read(line)
    } catch {
      case malformed: MalformedJsonException => throw new MalformedTestEventLogException(line, malformed.reason)
    }

  private def whole[A](reader: JsonObjectReader)(decodeFrom: JsonObjectReader => A): A = {
    val decoded = decodeFrom(reader)
    reader.requireNoOtherKey()
    decoded
  }

  private def logEvent(reader: JsonObjectReader): TestEventLogEvent = {
    reader.text("item") match {
      case "runStarted" => TestEventLogEvent.RunStarted(reader.long("pid"), reader.text("startTime"))
      case "structure" =>
        TestEventLogEvent.Structure(reader.required("nodes", reader.optionalObjects("nodes")).map(whole(_)(structureNode)))
      case "event" => TestEventLogEvent.ScalaTestEvent(nodeExecution(reader), scalaTestEvent(reader.text("event"), reader))
      case "runEnded" =>
        val outcome = reader.text("outcome")
        TestEventLogEvent.RunEnded(RunOutcome.fromWord(outcome).getOrElse(reader.malformed(s"unknown outcome $outcome")))
      case other => reader.malformed(s"unknown item $other")
    }
  }

  private def structureNode(reader: JsonObjectReader): StructureNode = {
    val id = reader.required("id", optionalNodePath(reader, "id"))
    val kind = reader.text("kind")
    val name = reader.text("name")
    kind match {
      case "suite" =>
        val parent = optionalNodePath(reader, "parent")
        val position = reader.optionalIntAtLeast("position", 0)
        val suiteId = SuiteId(reader.text("suiteId"))
        val className = reader.text("className")
        requireParentOnNodePath(reader, id, parent)
        StructureNode.Suite(id, parent, position, name, suiteId, className)
      case "scope" =>
        val scope = scopeOrTest(reader)(StructureNode.Scope(id, _, _, name, _))
        requireParentOnNodePath(reader, id, scope.parentId)
        scope
      case "test" =>
        val ignored = reader.trueOrAbsent("ignored")
        scopeOrTest(reader)(StructureNode.Test(id, _, _, name, _, ignored))
      case other => reader.malformed(s"unknown kind $other")
    }
  }

  private def scopeOrTest(reader: JsonObjectReader)(build: (NodeId, Int, String) => StructureNode): StructureNode = {
    val parent = reader.required("parent", optionalNodePath(reader, "parent"))
    val position = reader.intAtLeast("position", 0)
    val label = reader.text("label")
    build(parent, position, label)
  }

  private def requireParentOnNodePath(reader: JsonObjectReader, id: NodeId, parent: Option[NodeId]): Unit = {
    val pathParent = Option.when(id.places.size > 1)(NodeId(id.places.init))
    if (parent.isEmpty && pathParent.nonEmpty) {
      reader.malformed("missing key parent")
    } else if (parent != pathParent) {
      reader.malformed("parent is not the node's id without its last place")
    }
  }

  private def optionalNodePath(reader: JsonObjectReader, key: String): Option[NodeId] = {
    val places = reader.objects(key).map(whole(_)(place))
    Option.when(places.nonEmpty)(NodeId(places))
  }

  private def place(reader: JsonObjectReader): NodePlace = {
    if (reader.holds("suiteId")) {
      NodePlace.Suite(SuiteId(reader.text("suiteId")), reader.intAtLeast("index", 0))
    } else if (reader.holds("scope")) {
      NodePlace.Scope(reader.text("scope"), reader.intAtLeast("index", 0))
    } else if (reader.holds("test")) {
      NodePlace.Test(TestName(reader.text("test")))
    } else {
      reader.malformed("a place holds none of the keys suiteId, scope and test")
    }
  }

  private def nodeExecution(reader: JsonObjectReader): Option[NodeExecution] = {
    val node = optionalNodePath(reader, "node")
    val executions = reader.intsAtLeast("executions", 1)
    (node, executions.nonEmpty) match {
      case (Some(nodeId), true) => Some(NodeExecution(nodeId, executions))
      case (None, false) => None
      case (None, true) => reader.malformed("missing key node")
      case (Some(_), false) => reader.malformed("missing key executions")
    }
  }

  private def scalaTestEvent(eventName: String, reader: JsonObjectReader): DecodedEvent = {
    val throwables = new ThrowableDecoder
    def readMessage: String = reader.text("message")
    def readThrowable: Option[DecodedThrowable] = reader.optionalObject("throwable").map(throwables.decode)
    def readDuration: Option[Long] = reader.optionalLong("duration")
    def readSummary: Option[Summary] = reader.optionalObject("summary").map(whole(_)(summary))
    def readLocation: Option[Location] = reader.optionalObject("location").map(whole(_)(location))
    def readThreadName: String = reader.text("threadName")
    def readTimeStamp: Long = reader.long("timeStamp")

    def started(build: (Option[Location], String, Long) => DecodedEvent): DecodedEvent =
      build(readLocation, readThreadName, readTimeStamp)
    def timed(build: (Option[Long], Option[Location], String, Long) => DecodedEvent): DecodedEvent = {
      val duration = readDuration
      started(build(duration, _, _, _))
    }
    def withMessage(build: (String, Option[DecodedThrowable], Option[Location], String, Long) => DecodedEvent): DecodedEvent = {
      val message = readMessage
      val throwable = readThrowable
      started(build(message, throwable, _, _, _))
    }
    def stoppedByThrowable(
      build: (String, Option[DecodedThrowable], Option[Long], Option[Location], String, Long) => DecodedEvent
    ): DecodedEvent = {
      val message = readMessage
      val throwable = readThrowable
      timed(build(message, throwable, _, _, _, _))
    }
    def runClosed(build: (Option[Long], Option[Summary], Option[Location], String, Long) => DecodedEvent): DecodedEvent = {
      val duration = readDuration
      val summary = readSummary
      started(build(duration, summary, _, _, _))
    }

    eventName match {
      case "TestStarting" => started(DecodedEvent.TestStarting(_, _, _))
      case "TestSucceeded" => timed(DecodedEvent.TestSucceeded(_, _, _, _))
      case "TestFailed" =>
        val message = readMessage
        val analysis = reader.texts("analysis")
        val throwable = readThrowable
        timed(DecodedEvent.TestFailed(message, analysis, throwable, _, _, _, _))
      case "TestIgnored" => started(DecodedEvent.TestIgnored(_, _, _))
      case "TestPending" => timed(DecodedEvent.TestPending(_, _, _, _))
      case "TestCanceled" => stoppedByThrowable(DecodedEvent.TestCanceled(_, _, _, _, _, _))
      case "SuiteStarting" => started(DecodedEvent.SuiteStarting(_, _, _))
      case "SuiteCompleted" => timed(DecodedEvent.SuiteCompleted(_, _, _, _))
      case "SuiteAborted" => stoppedByThrowable(DecodedEvent.SuiteAborted(_, _, _, _, _, _))
      case "RunStarting" =>
        val testCount = reader.int("testCount")
        val configMap = reader.required("configMap", reader.optionalObject("configMap")).allTexts
        started(DecodedEvent.RunStarting(testCount, configMap, _, _, _))
      case "RunCompleted" => runClosed(DecodedEvent.RunCompleted(_, _, _, _, _))
      case "RunStopped" => runClosed(DecodedEvent.RunStopped(_, _, _, _, _))
      case "RunAborted" =>
        val message = readMessage
        val throwable = readThrowable
        runClosed(DecodedEvent.RunAborted(message, throwable, _, _, _, _, _))
      case "InfoProvided" => withMessage(DecodedEvent.InfoProvided(_, _, _, _, _))
      case "AlertProvided" => withMessage(DecodedEvent.AlertProvided(_, _, _, _, _))
      case "NoteProvided" => withMessage(DecodedEvent.NoteProvided(_, _, _, _, _))
      case "MarkupProvided" =>
        val text = reader.text("text")
        started(DecodedEvent.MarkupProvided(text, _, _, _))
      case "ScopeOpened" => started(DecodedEvent.ScopeOpened(_, _, _))
      case "ScopeClosed" => started(DecodedEvent.ScopeClosed(_, _, _))
      case "ScopePending" => started(DecodedEvent.ScopePending(_, _, _))
      case "DiscoveryStarting" => DecodedEvent.DiscoveryStarting(readThreadName, readTimeStamp)
      case "DiscoveryCompleted" => DecodedEvent.DiscoveryCompleted(readDuration, readThreadName, readTimeStamp)
      case other => reader.malformed(s"unknown event $other")
    }
  }

  private def location(reader: JsonObjectReader): Location = {
    reader.text("kind") match {
      case "TopOfClass" => TopOfClass(reader.text("className"))
      case "TopOfMethod" => TopOfMethod(reader.text("className"), reader.text("methodId"))
      case "LineInFile" => LineInFile(reader.int("lineNumber"), reader.text("fileName"), reader.optionalText("filePathname"))
      case "SeeStackDepthException" => SeeStackDepthException
      case other => reader.malformed(s"unknown kind $other")
    }
  }

  private def summary(reader: JsonObjectReader): Summary = {
    Summary(
      reader.int("testsSucceededCount"),
      reader.int("testsFailedCount"),
      reader.int("testsIgnoredCount"),
      reader.int("testsPendingCount"),
      reader.int("testsCanceledCount"),
      reader.int("suitesCompletedCount"),
      reader.int("suitesAbortedCount"),
      reader.int("scopesPendingCount")
    )
  }

  private final class ThrowableDecoder {

    private val decoded = mutable.Map.empty[Long, DecodedThrowable]

    def decode(reader: JsonObjectReader): DecodedThrowable = {
      whole(reader)(throwableOrRef) match {
        case Left(ref) => ref
        case Right(opened) => OpenThrowable.closedFrom(opened)(linked => whole(linked)(throwableOrRef))
      }
    }

    private def throwableOrRef(reader: JsonObjectReader): Either[DecodedThrowable, DecodingThrowable] = {
      reader.optionalLong("ref") match {
        case Some(id) => Left(decoded.getOrElse(id, reader.malformed(s"ref $id names no throwable the event holds before it")))
        case None =>
          val id = reader.longAtLeast("id", 1L)
          val opened = new DecodingThrowable(
            className = reader.text("className"),
            message = reader.optionalText("message"),
            stackDepth = reader.optionalObject("stackDepth").map(whole(_)(stackDepth)),
            isPropertyCheckFailure = reader.trueOrAbsent("propertyCheck"),
            stackTrace = reader.objects("stackTrace").map(whole(_)(frame)),
            cause = reader.optionalObject("cause"),
            suppressed = reader.objects("suppressed").iterator
          )
          // Registered before the throwable's links are read, so that a ref among them yields the throwable.
          registered(reader, id, opened.closed)
          Right(opened)
      }
    }

    private def registered(reader: JsonObjectReader, id: Long, throwable: DecodedThrowable): Unit = {
      if (decoded.contains(id)) {
        reader.malformed(s"two throwables have id $id")
      }
      decoded.update(id, throwable)
    }

    private def stackDepth(reader: JsonObjectReader): DecodedStackDepth =
      DecodedStackDepth(reader.optionalText("fileName"), reader.optionalText("filePathname"), reader.optionalInt("lineNumber"))

    private def frame(reader: JsonObjectReader): DecodedStackFrame = {
      DecodedStackFrame(
        reader.text("className"),
        reader.text("methodName"),
        reader.optionalText("fileName"),
        reader.optionalInt("lineNumber"),
        reader.trueOrAbsent("isNative"),
        reader.text("text")
      )
    }
  }

  private final class DecodingThrowable(
    className: String,
    message: Option[String],
    stackDepth: Option[DecodedStackDepth],
    isPropertyCheckFailure: Boolean,
    stackTrace: IndexedSeq[DecodedStackFrame],
    cause: Option[JsonObjectReader],
    suppressed: Iterator[JsonObjectReader]
  ) extends OpenThrowable[JsonObjectReader, DecodedThrowable](cause, suppressed) {

    val closed: DecodedThrowable =
      new DecodedThrowable(className, message, stackDepth, isPropertyCheckFailure, stackTrace, linkedCause, linkedSuppressed)
  }
}

private final class JsonObjectReader(line: String, fields: Vector[(String, JsonValue)]) {

  private val values = fields.toMap
  private val keysAskedFor = mutable.Set.empty[String]

  def malformed(reason: String): Nothing = throw new MalformedTestEventLogException(line, reason)

  def requireNoOtherKey(): Unit =
    fields.collectFirst { case (key, _) if !keysAskedFor.contains(key) => key }.foreach(key => malformed(s"unknown key $key"))

  def required[A](key: String, value: Option[A]): A = value.getOrElse(malformed(s"missing key $key"))

  def text(key: String): String = required(key, optionalText(key))

  def optionalText(key: String): Option[String] =
    optional(key, "a string") { case JsonValue.Text(value) => value }

  def long(key: String): Long = required(key, optionalLong(key))

  def optionalLong(key: String): Option[Long] =
    optional(key, "an integer") { case JsonValue.Integer(value) => value }

  def longAtLeast(key: String, minimum: Long): Long = {
    required(key, optional(key, s"an integer from $minimum") { case JsonValue.Integer(value) if value >= minimum => value })
  }

  def int(key: String): Int = required(key, optionalInt(key))

  def optionalInt(key: String): Option[Int] =
    optional(key, "a 32-bit integer") { case JsonValue.Integer(value) if value.isValidInt => value.toInt }

  def intAtLeast(key: String, minimum: Int): Int = required(key, optionalIntAtLeast(key, minimum))

  def optionalIntAtLeast(key: String, minimum: Int): Option[Int] = {
    optional(key, s"a 32-bit integer from $minimum") {
      case JsonValue.Integer(value) if value.isValidInt && value >= minimum => value.toInt
    }
  }

  def trueOrAbsent(key: String): Boolean =
    optional(key, "true") { case JsonValue.Bool(true) => true }.isDefined

  def optionalObject(key: String): Option[JsonObjectReader] =
    optional(key, "an object") { case JsonValue.Object(nestedFields) => new JsonObjectReader(line, nestedFields) }

  def objects(key: String): Vector[JsonObjectReader] = optionalObjects(key).getOrElse(Vector.empty)

  def optionalObjects(key: String): Option[Vector[JsonObjectReader]] = {
    optional(key, "an array of objects") {
      case JsonValue.Array(items) if items.forall(_.isInstanceOf[JsonValue.Object]) =>
        items.collect { case JsonValue.Object(nestedFields) => new JsonObjectReader(line, nestedFields) }
    }
  }

  def holds(key: String): Boolean = values.contains(key)

  def intsAtLeast(key: String, minimum: Int): Vector[Int] = {
    optional(key, s"an array of 32-bit integers from $minimum") {
      case JsonValue.Array(items) if items.forall(isIntAtLeast(minimum)) =>
        items.collect { case JsonValue.Integer(value) => value.toInt }
    }.getOrElse(Vector.empty)
  }

  def texts(key: String): Vector[String] = {
    optional(key, "an array of strings") {
      case JsonValue.Array(items) if items.forall(_.isInstanceOf[JsonValue.Text]) =>
        items.collect { case JsonValue.Text(value) => value }
    }.getOrElse(Vector.empty)
  }

  def allTexts: Map[String, String] = {
    keysAskedFor ++= values.keys
    fields.collectFirst { case (key, value) if !value.isInstanceOf[JsonValue.Text] => key }.foreach { key =>
      malformed(s"$key is not a string")
    }
    fields.collect { case (key, JsonValue.Text(value)) => key -> value }.toMap
  }

  private def optional[A](key: String, expected: String)(read: PartialFunction[JsonValue, A]): Option[A] = {
    keysAskedFor += key
    values.get(key).map(value => read.applyOrElse(value, (_: JsonValue) => malformed(s"$key is not $expected")))
  }

  private def isIntAtLeast(minimum: Int)(value: JsonValue): Boolean = {
    value match {
      case JsonValue.Integer(number) => number.isValidInt && number >= minimum
      case _ => false
    }
  }
}
