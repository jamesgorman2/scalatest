package org.scalatest.events

import scala.annotation.tailrec
import scala.collection.mutable

private[scalatest] object TestEventLogDecoder {

  def decode(line: String): Either[String, TestEventLogEvent] = {
    JsonReader.read(line).flatMap {
      case JsonValue.Object(fields) => whole(new JsonObjectReader(fields))(logEvent)
      case _ => Left("the line is not a JSON object")
    }
  }

  private def whole[A](reader: JsonObjectReader)(decodeFrom: JsonObjectReader => Either[String, A]): Either[String, A] =
    decodeFrom(reader).flatMap(reader.withNoOtherKey)

  private def each[A, B](items: Vector[A])(decodeItem: A => Either[String, B]): Either[String, Vector[B]] = {
    items.foldLeft[Either[String, Vector[B]]](Right(Vector.empty)) { (decoded, item) =>
      decoded.flatMap(earlier => decodeItem(item).map(earlier :+ _))
    }
  }

  private def ifPresent[A, B](value: Option[A])(decodeValue: A => Either[String, B]): Either[String, Option[B]] = {
    value match {
      case Some(present) => decodeValue(present).map(Some(_))
      case None => Right(None)
    }
  }

  private def logEvent(reader: JsonObjectReader): Either[String, TestEventLogEvent] = {
    reader.text("item").flatMap {
      case "runStarted" =>
        for {
          pid <- reader.long("pid")
          startTime <- reader.text("startTime")
        } yield TestEventLogEvent.RunStarted(pid, startTime)
      case "structure" =>
        for {
          nodeReaders <- reader.required("nodes", reader.optionalObjects("nodes"))
          nodes <- each(nodeReaders)(whole(_)(structureNode))
        } yield TestEventLogEvent.Structure(nodes)
      case "event" =>
        for {
          execution <- nodeExecution(reader)
          eventName <- reader.text("event")
          event <- scalaTestEvent(eventName, reader)
        } yield TestEventLogEvent.ScalaTestEvent(execution, event)
      case "runEnded" =>
        reader.text("outcome").flatMap {
          case "completed" => Right(TestEventLogEvent.RunEnded(RunOutcome.Completed))
          case "aborted" => Right(TestEventLogEvent.RunEnded(RunOutcome.Aborted))
          case "stopped" => Right(TestEventLogEvent.RunEnded(RunOutcome.Stopped))
          case other => Left(s"unknown outcome $other")
        }
      case other => Left(s"unknown item $other")
    }
  }

  private def structureNode(reader: JsonObjectReader): Either[String, StructureNode] = {
    for {
      id <- reader.long("id")
      kind <- reader.text("kind")
      name <- reader.text("name")
      node <- kind match {
        case "suite" =>
          for {
            parent <- reader.optionalLong("parent")
            position <- reader.optionalIntFrom("position", 0)
            suiteId <- reader.text("suiteId")
            className <- reader.text("className")
          } yield StructureNode.Suite(NodeId(id), parent.map(NodeId(_)), position, name, suiteId, className)
        case "scope" => scopeOrTest(reader)(StructureNode.Scope(NodeId(id), _, _, name, _))
        case "test" => scopeOrTest(reader)(StructureNode.Test(NodeId(id), _, _, name, _))
        case other => Left(s"unknown kind $other")
      }
    } yield node
  }

  private def scopeOrTest(reader: JsonObjectReader)(build: (NodeId, Int, String) => StructureNode): Either[String, StructureNode] = {
    for {
      parent <- reader.long("parent")
      position <- reader.intFrom("position", 0)
      label <- reader.text("label")
    } yield build(NodeId(parent), position, label)
  }

  private def nodeExecution(reader: JsonObjectReader): Either[String, Option[NodeExecution]] = {
    for {
      node <- reader.optionalLong("node")
      execution <- reader.optionalIntFrom("execution", 1)
      parentExecution <- reader.optionalIntFrom("parentExecution", 1)
      assigned <- (node, execution) match {
        case (Some(nodeId), Some(number)) => Right(Some(NodeExecution(NodeId(nodeId), number, parentExecution)))
        case (None, None) if parentExecution.isEmpty => Right(None)
        case (None, _) => Left("missing key node")
        case (Some(_), None) => Left("missing key execution")
      }
    } yield assigned
  }

  private def scalaTestEvent(eventName: String, reader: JsonObjectReader): Either[String, DecodedEvent] = {
    val throwables = new ThrowableDecoder
    def readMessage: Either[String, String] = reader.text("message")
    def readThrowable: Either[String, Option[DecodedThrowable]] =
      reader.optionalObject("throwable").flatMap(ifPresent(_)(throwables.decode))
    def readDuration: Either[String, Option[Long]] = reader.optionalLong("duration")
    def readSummary: Either[String, Option[Summary]] =
      reader.optionalObject("summary").flatMap(ifPresent(_)(whole(_)(decodeSummary)))
    def readLocation: Either[String, Option[Location]] =
      reader.optionalObject("location").flatMap(ifPresent(_)(whole(_)(decodeLocation)))
    def readThreadName: Either[String, String] = reader.text("threadName")
    def readTimeStamp: Either[String, Long] = reader.long("timeStamp")

    def started(build: (Option[Location], String, Long) => DecodedEvent): Either[String, DecodedEvent] = {
      for {
        location <- readLocation
        threadName <- readThreadName
        timeStamp <- readTimeStamp
      } yield build(location, threadName, timeStamp)
    }
    def timed(build: (Option[Long], Option[Location], String, Long) => DecodedEvent): Either[String, DecodedEvent] = {
      for {
        duration <- readDuration
        event <- started(build(duration, _, _, _))
      } yield event
    }
    def withMessage(
      build: (String, Option[DecodedThrowable], Option[Location], String, Long) => DecodedEvent
    ): Either[String, DecodedEvent] = {
      for {
        message <- readMessage
        throwable <- readThrowable
        event <- started(build(message, throwable, _, _, _))
      } yield event
    }
    def stoppedByThrowable(
      build: (String, Option[DecodedThrowable], Option[Long], Option[Location], String, Long) => DecodedEvent
    ): Either[String, DecodedEvent] = {
      for {
        message <- readMessage
        throwable <- readThrowable
        event <- timed(build(message, throwable, _, _, _, _))
      } yield event
    }
    def runClosed(
      build: (Option[Long], Option[Summary], Option[Location], String, Long) => DecodedEvent
    ): Either[String, DecodedEvent] = {
      for {
        duration <- readDuration
        summary <- readSummary
        event <- started(build(duration, summary, _, _, _))
      } yield event
    }

    eventName match {
      case "TestStarting" => started(DecodedEvent.TestStarting(_, _, _))
      case "TestSucceeded" => timed(DecodedEvent.TestSucceeded(_, _, _, _))
      case "TestFailed" =>
        for {
          message <- readMessage
          analysis <- reader.texts("analysis")
          throwable <- readThrowable
          event <- timed(DecodedEvent.TestFailed(message, analysis, throwable, _, _, _, _))
        } yield event
      case "TestIgnored" => started(DecodedEvent.TestIgnored(_, _, _))
      case "TestPending" => timed(DecodedEvent.TestPending(_, _, _, _))
      case "TestCanceled" => stoppedByThrowable(DecodedEvent.TestCanceled(_, _, _, _, _, _))
      case "SuiteStarting" => started(DecodedEvent.SuiteStarting(_, _, _))
      case "SuiteCompleted" => timed(DecodedEvent.SuiteCompleted(_, _, _, _))
      case "SuiteAborted" => stoppedByThrowable(DecodedEvent.SuiteAborted(_, _, _, _, _, _))
      case "RunStarting" =>
        for {
          testCount <- reader.int("testCount")
          configMap <- reader.required("configMap", reader.optionalObject("configMap")).flatMap(_.allTexts)
          event <- started(DecodedEvent.RunStarting(testCount, configMap, _, _, _))
        } yield event
      case "RunCompleted" => runClosed(DecodedEvent.RunCompleted(_, _, _, _, _))
      case "RunStopped" => runClosed(DecodedEvent.RunStopped(_, _, _, _, _))
      case "RunAborted" =>
        for {
          message <- readMessage
          throwable <- readThrowable
          event <- runClosed(DecodedEvent.RunAborted(message, throwable, _, _, _, _, _))
        } yield event
      case "InfoProvided" => withMessage(DecodedEvent.InfoProvided(_, _, _, _, _))
      case "AlertProvided" => withMessage(DecodedEvent.AlertProvided(_, _, _, _, _))
      case "NoteProvided" => withMessage(DecodedEvent.NoteProvided(_, _, _, _, _))
      case "MarkupProvided" =>
        for {
          text <- reader.text("text")
          event <- started(DecodedEvent.MarkupProvided(text, _, _, _))
        } yield event
      case "ScopeOpened" => started(DecodedEvent.ScopeOpened(_, _, _))
      case "ScopeClosed" => started(DecodedEvent.ScopeClosed(_, _, _))
      case "ScopePending" => started(DecodedEvent.ScopePending(_, _, _))
      case "DiscoveryStarting" =>
        for {
          threadName <- readThreadName
          timeStamp <- readTimeStamp
        } yield DecodedEvent.DiscoveryStarting(threadName, timeStamp)
      case "DiscoveryCompleted" =>
        for {
          duration <- readDuration
          threadName <- readThreadName
          timeStamp <- readTimeStamp
        } yield DecodedEvent.DiscoveryCompleted(duration, threadName, timeStamp)
      case other => Left(s"unknown event $other")
    }
  }

  private def decodeLocation(reader: JsonObjectReader): Either[String, Location] = {
    reader.text("kind").flatMap {
      case "TopOfClass" => reader.text("className").map(TopOfClass(_))
      case "TopOfMethod" =>
        for {
          className <- reader.text("className")
          methodId <- reader.text("methodId")
        } yield TopOfMethod(className, methodId)
      case "LineInFile" =>
        for {
          lineNumber <- reader.int("lineNumber")
          fileName <- reader.text("fileName")
          filePathname <- reader.optionalText("filePathname")
        } yield LineInFile(lineNumber, fileName, filePathname)
      case "SeeStackDepthException" => Right(SeeStackDepthException)
      case other => Left(s"unknown kind $other")
    }
  }

  private def decodeSummary(reader: JsonObjectReader): Either[String, Summary] = {
    for {
      testsSucceededCount <- reader.int("testsSucceededCount")
      testsFailedCount <- reader.int("testsFailedCount")
      testsIgnoredCount <- reader.int("testsIgnoredCount")
      testsPendingCount <- reader.int("testsPendingCount")
      testsCanceledCount <- reader.int("testsCanceledCount")
      suitesCompletedCount <- reader.int("suitesCompletedCount")
      suitesAbortedCount <- reader.int("suitesAbortedCount")
      scopesPendingCount <- reader.int("scopesPendingCount")
    } yield Summary(
      testsSucceededCount,
      testsFailedCount,
      testsIgnoredCount,
      testsPendingCount,
      testsCanceledCount,
      suitesCompletedCount,
      suitesAbortedCount,
      scopesPendingCount
    )
  }

  // Holds the throwables one event has read, by ID, so that a ref yields the object its ID yielded.
  private final class ThrowableDecoder {

    private val decoded = mutable.Map.empty[Long, DecodedThrowable]

    def decode(reader: JsonObjectReader): Either[String, DecodedThrowable] = {
      whole(reader)(throwableOrRef).flatMap { case (throwable, opened) =>
        linked(opened.toList).map(_ => throwable)
      }
    }

    // Keeps the throwables whose causes and suppressed throwables are still to read in a list rather than on the
    // call stack, so that no length of cause chain overflows it.
    @tailrec
    private def linked(open: List[OpenThrowable]): Either[String, Unit] = {
      open match {
        case Nil => Right(())
        case innermost :: outer =>
          innermost.nextLinked() match {
            case None =>
              innermost.close()
              linked(outer)
            case Some(reader) =>
              whole(reader)(throwableOrRef) match {
                case Left(reason) => Left(reason)
                case Right((throwable, opened)) =>
                  innermost.add(throwable)
                  linked(opened.toList ::: open)
              }
          }
      }
    }

    // Yields a ref's throwable alone, and a throwable the event has not yet read with its cause and suppressed
    // throwables still to read.
    private def throwableOrRef(reader: JsonObjectReader): Either[String, (DecodedThrowable, Option[OpenThrowable])] = {
      reader.optionalLong("ref").flatMap {
        case Some(id) => decoded.get(id).toRight(s"ref $id names no throwable the event holds before it").map(_ -> None)
        case None =>
          for {
            id <- reader.longFrom("id", 1L)
            className <- reader.text("className")
            message <- reader.optionalText("message")
            stackDepth <- reader.optionalObject("stackDepth").flatMap(ifPresent(_)(whole(_)(decodeStackDepth)))
            isPropertyCheckFailure <- reader.isTrue("propertyCheck")
            stackTrace <- reader.objects("stackTrace").flatMap(each(_)(whole(_)(frame)))
            cause <- reader.optionalObject("cause")
            suppressed <- reader.objects("suppressed")
            throwable <- registered(id, new DecodedThrowable(className, message, stackDepth, isPropertyCheckFailure, stackTrace))
          } yield throwable -> Some(new OpenThrowable(throwable, cause, suppressed.iterator))
      }
    }

    private def registered(id: Long, throwable: DecodedThrowable): Either[String, DecodedThrowable] = {
      if (decoded.contains(id)) {
        Left(s"two throwables have id $id")
      } else {
        decoded.update(id, throwable)
        Right(throwable)
      }
    }

    private def decodeStackDepth(reader: JsonObjectReader): Either[String, DecodedStackDepth] = {
      for {
        fileName <- reader.optionalText("fileName")
        filePathname <- reader.optionalText("filePathname")
        lineNumber <- reader.optionalInt("lineNumber")
      } yield DecodedStackDepth(fileName, filePathname, lineNumber)
    }

    private def frame(reader: JsonObjectReader): Either[String, DecodedStackFrame] = {
      for {
        className <- reader.text("className")
        methodName <- reader.text("methodName")
        fileName <- reader.optionalText("fileName")
        lineNumber <- reader.optionalInt("lineNumber")
        isNative <- reader.isTrue("isNative")
        text <- reader.text("text")
      } yield DecodedStackFrame(className, methodName, fileName, lineNumber, isNative, text)
    }
  }

  // A throwable whose own fields are read, taking its cause and then each suppressed throwable as they are read.
  private final class OpenThrowable(
    throwable: DecodedThrowable,
    cause: Option[JsonObjectReader],
    suppressed: Iterator[JsonObjectReader]
  ) {

    private var causeIsNext = cause.nonEmpty
    private var addsCause = false
    private var readCause: Option[DecodedThrowable] = None
    private val readSuppressed = Vector.newBuilder[DecodedThrowable]

    def nextLinked(): Option[JsonObjectReader] = {
      addsCause = causeIsNext
      causeIsNext = false
      if (addsCause) cause else suppressed.nextOption()
    }

    def add(linked: DecodedThrowable): Unit = {
      if (addsCause) {
        readCause = Some(linked)
      } else {
        readSuppressed += linked
      }
    }

    def close(): Unit = throwable.link(readCause, readSuppressed.result())
  }
}

// Records each key it is asked for, so that a key the format does not give the object is one never asked for.
private final class JsonObjectReader(fields: Vector[(String, JsonValue)]) {

  private val values = fields.toMap
  private val keysAskedFor = mutable.Set.empty[String]

  def withNoOtherKey[A](decoded: A): Either[String, A] = {
    fields.collectFirst { case (key, _) if !keysAskedFor.contains(key) => key } match {
      case Some(key) => Left(s"unknown key $key")
      case None => Right(decoded)
    }
  }

  def required[A](key: String, value: Either[String, Option[A]]): Either[String, A] =
    value.flatMap(_.toRight(s"missing key $key"))

  def text(key: String): Either[String, String] = required(key, optionalText(key))

  def optionalText(key: String): Either[String, Option[String]] =
    optional(key, "a string") { case JsonValue.Text(value) => value }

  def long(key: String): Either[String, Long] = required(key, optionalLong(key))

  def optionalLong(key: String): Either[String, Option[Long]] =
    optional(key, "an integer") { case JsonValue.Integer(value) => value }

  def longFrom(key: String, minimum: Long): Either[String, Long] = {
    required(key, optional(key, s"an integer from $minimum") { case JsonValue.Integer(value) if value >= minimum => value })
  }

  def int(key: String): Either[String, Int] = required(key, optionalInt(key))

  def optionalInt(key: String): Either[String, Option[Int]] =
    optional(key, "a 32-bit integer") { case JsonValue.Integer(value) if value.isValidInt => value.toInt }

  def intFrom(key: String, minimum: Int): Either[String, Int] = required(key, optionalIntFrom(key, minimum))

  def optionalIntFrom(key: String, minimum: Int): Either[String, Option[Int]] = {
    optional(key, s"a 32-bit integer from $minimum") {
      case JsonValue.Integer(value) if value.isValidInt && value >= minimum => value.toInt
    }
  }

  def isTrue(key: String): Either[String, Boolean] =
    optional(key, "true or false") { case JsonValue.Bool(value) => value }.map(_.getOrElse(false))

  def optionalObject(key: String): Either[String, Option[JsonObjectReader]] =
    optional(key, "an object") { case JsonValue.Object(nestedFields) => new JsonObjectReader(nestedFields) }

  def objects(key: String): Either[String, Vector[JsonObjectReader]] =
    optionalObjects(key).map(_.getOrElse(Vector.empty))

  def optionalObjects(key: String): Either[String, Option[Vector[JsonObjectReader]]] = {
    optional(key, "an array of objects") {
      case JsonValue.Array(items) if items.forall(_.isInstanceOf[JsonValue.Object]) =>
        items.collect { case JsonValue.Object(nestedFields) => new JsonObjectReader(nestedFields) }
    }
  }

  def texts(key: String): Either[String, Vector[String]] = {
    optional(key, "an array of strings") {
      case JsonValue.Array(items) if items.forall(_.isInstanceOf[JsonValue.Text]) =>
        items.collect { case JsonValue.Text(value) => value }
    }.map(_.getOrElse(Vector.empty))
  }

  def allTexts: Either[String, Map[String, String]] = {
    keysAskedFor ++= values.keys
    fields.collectFirst { case (key, value) if !value.isInstanceOf[JsonValue.Text] => key } match {
      case Some(key) => Left(s"$key is not a string")
      case None => Right(fields.collect { case (key, JsonValue.Text(value)) => key -> value }.toMap)
    }
  }

  private def optional[A](key: String, expected: String)(read: PartialFunction[JsonValue, A]): Either[String, Option[A]] = {
    keysAskedFor += key
    values.get(key) match {
      case Some(value) => read.lift(value).map(Some(_)).toRight(s"$key is not $expected")
      case None => Right(None)
    }
  }
}
