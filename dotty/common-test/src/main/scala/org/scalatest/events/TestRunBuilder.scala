package org.scalatest.events

import scala.collection.mutable

private[scalatest] enum CapturedStream {
  case Stdout
  case Stderr
}

private[scalatest] enum TestRunPart {
  case Events(notation: String)
  case CapturedLine(stream: CapturedStream, line: String)
}

private[scalatest] final case class FixedParts(fields: Set[String], parentExecution: Boolean, throwable: Option[WrittenThrowable])

private[scalatest] object FixedParts {
  val none: FixedParts = FixedParts(Set.empty, false, None)
}

private[scalatest] final case class BuiltEvent(notation: String, event: TestEventLogEvent, fixed: FixedParts)

private[scalatest] enum BuiltRunItem {
  case Event(built: BuiltEvent)
  case CapturedLine(stream: CapturedStream, line: String)
}

private[scalatest] final case class BuiltRun(items: Vector[BuiltRunItem]) {

  def events: Vector[BuiltEvent] = items.collect { case BuiltRunItem.Event(built) => built }
}

/**
 * Builds a run from the event notation the acceptance criteria use, in the form the test event log's decoder
 * yields it.
 *
 * A run is given as its parts in order: blocks of events in the notation, and captured lines where they are fed.
 * Each event is built as a `Given` gives it, with a default for every field it does not write, and carries what a
 * `Then` fixes of it for `EventLogComparison`.
 */
private[scalatest] object TestRunBuilder {

  val defaultPid: Long = 1L
  val defaultStartTime: String = "1970-01-01T00:00:00Z"
  val defaultThreadName: String = "main"
  val defaultTimeStamp: Long = 0L

  /** Returns the built run, or why a part is not in the notation. */
  def build(parts: TestRunPart*): Either[String, BuiltRun] = {
    val run = new RunUnderConstruction
    try {
      Right(
        BuiltRun(
          parts.toVector.flatMap {
            case TestRunPart.Events(notation) => EventNotation.parse(notation).map(line => BuiltRunItem.Event(run.built(line)))
            case TestRunPart.CapturedLine(stream, line) => Vector(BuiltRunItem.CapturedLine(stream, line))
          }
        )
      )
    } catch {
      case error: NotationError => Left(error.getMessage)
    }
  }
}

private final class HeldNode(val id: NodeId, val kind: NodeKind, var name: String, var parent: Option[NodeId]) {

  private var executions = Set.empty[Int]
  private var endedExecutions = Set.empty[Int]

  def record(execution: Int, endsIt: Boolean): Unit = {
    executions += execution
    if (endsIt) {
      endedExecutions += execution
    }
  }

  def latestOpenExecution: Option[Int] = (executions -- endedExecutions).maxOption

  def latestExecution: Option[Int] = executions.maxOption
}

private final class SuiteTable(
  val id: NodeId,
  val suiteId: String,
  val className: String,
  val parent: Option[NodeId],
  val position: Option[Int]
) {
  val nestedSuites: mutable.ArrayBuffer[SuiteTable] = mutable.ArrayBuffer.empty
  val testIds: mutable.Map[String, NodeId] = mutable.Map.empty
  val scopeIds: mutable.Map[(NodeId, String, Int), NodeId] = mutable.Map.empty
}

private final class OpenTreeNode(
  val depth: Int,
  val id: NodeId,
  val kind: NodeKind,
  val suite: SuiteTable,
  val scopeNames: Vector[String]
) {
  private var children = 0
  private val scopesNamed = mutable.Map.empty[String, Int]

  def nextPosition(): Int = {
    children += 1
    children - 1
  }

  def nextPlaceOfScopeNamed(name: String): Int = {
    val place = scopesNamed.getOrElse(name, 0)
    scopesNamed(name) = place + 1
    place
  }
}

private final class RunUnderConstruction {

  private var mintedNodes = 0L
  private val topLevelSuites = mutable.ArrayBuffer.empty[SuiteTable]
  private val suiteTables = mutable.Map.empty[NodeId, SuiteTable]
  private val nodesInOrderFirstHeld = mutable.ArrayBuffer.empty[HeldNode]
  private val nodes = mutable.Map.empty[NodeId, HeldNode]

  def built(line: NotationLine): BuiltEvent = {
    line match {
      case NotationLine.RunStarted(text) =>
        BuiltEvent(text, TestEventLogEvent.RunStarted(TestRunBuilder.defaultPid, TestRunBuilder.defaultStartTime), FixedParts.none)
      case block: NotationLine.Structure =>
        BuiltEvent(block.text, TestEventLogEvent.Structure(structureNodes(block)), FixedParts.none)
      case event: NotationLine.Event => scalaTestEvent(event)
      case NotationLine.RunEnded(outcome, text) => BuiltEvent(text, TestEventLogEvent.RunEnded(outcome), FixedParts.none)
    }
  }

  private def fail(reason: String): Nothing = throw new NotationError(reason)

  private def minted(): NodeId = {
    mintedNodes += 1
    NodeId(mintedNodes)
  }

  private def hold(id: NodeId, kind: NodeKind, name: String, parent: Option[NodeId]): Unit = {
    nodes.get(id) match {
      case Some(held) =>
        held.name = name
        held.parent = parent
      case None =>
        val held = new HeldNode(id, kind, name, parent)
        nodes(id) = held
        nodesInOrderFirstHeld += held
    }
  }

  private def nodeNamed(reference: NodeReference): HeldNode = {
    val sharingName = nodesInOrderFirstHeld.filter(held => held.kind == reference.kind && held.name == reference.name)
    reference.ordinal match {
      case Some(ordinal) if ordinal <= sharingName.size => sharingName(ordinal - 1)
      case Some(_) => fail(s"no structure so far holds ${reference.text}: ${sharingName.size} share that name")
      case None if sharingName.size == 1 => sharingName.head
      case None if sharingName.isEmpty => fail(s"no structure so far holds ${reference.text}")
      case None => fail(s"the structures so far hold ${sharingName.size} nodes named ${reference.text}: add #<n>")
    }
  }

  private def structureNodes(block: NotationLine.Structure): Vector[StructureNode] = {
    val (rootName, rootClassName, rootSuiteId) = block.tree.headOption match {
      case Some(TreeLine(1, WrittenNode.Suite(name, className, suiteId), _)) => (name, className, suiteId.getOrElse(className))
      case _ => fail(s"a structure starts with its suite, indented 2 spaces:\n${block.text}")
    }
    val parent = block.under.map(reference => suiteTables(nodeNamed(reference).id))
    val siblings = parent.fold(topLevelSuites)(_.nestedSuites)
    val root = siblings.find(_.suiteId == rootSuiteId) match {
      case Some(earlier) =>
        if (earlier.className != rootClassName) {
          fail(s"a further structure keeps its suite's class, ${earlier.className}:\n${block.text}")
        }
        block.tree.tail.collectFirst { case TreeLine(_, _: WrittenNode.Suite, text) => text }.foreach { nested =>
          fail(s"a further structure holds its suite's scopes and tests only, not '$nested'")
        }
        earlier
      case None =>
        val added = new SuiteTable(minted(), rootSuiteId, rootClassName, parent.map(_.id), None)
        siblings += added
        suiteTables(added.id) = added
        added
    }
    hold(root.id, NodeKind.Suite, rootName, root.parent)

    val built = Vector.newBuilder[StructureNode]
    built += StructureNode.Suite(root.id, root.parent, root.position, rootName, root.suiteId, root.className)
    val testNames = mutable.Set.empty[(NodeId, String)]
    var open = List(new OpenTreeNode(1, root.id, NodeKind.Suite, root, Vector.empty))
    block.tree.tail.foreach { line =>
      open = open.dropWhile(_.depth >= line.depth)
      val above = open.headOption.filter(_.depth == line.depth - 1).getOrElse {
        fail(s"'${line.text}' is not indented 2 spaces under a suite or scope of its structure")
      }
      line.node match {
        case WrittenNode.Suite(name, className, suiteId) =>
          if (above.kind != NodeKind.Suite) {
            fail(s"a suite is nested in a suite, not in a scope: '${line.text}'")
          }
          val nested = new SuiteTable(minted(), suiteId.getOrElse(className), className, Some(above.id), Some(above.nextPosition()))
          above.suite.nestedSuites += nested
          suiteTables(nested.id) = nested
          hold(nested.id, NodeKind.Suite, name, nested.parent)
          built += StructureNode.Suite(nested.id, nested.parent, nested.position, name, nested.suiteId, className)
          open = new OpenTreeNode(line.depth, nested.id, NodeKind.Suite, nested, Vector.empty) :: open
        case WrittenNode.Scope(name, label) =>
          val place = above.nextPlaceOfScopeNamed(name)
          val id = above.suite.scopeIds.getOrElseUpdate((above.id, name, place), minted())
          hold(id, NodeKind.Scope, name, Some(above.id))
          built += StructureNode.Scope(id, above.id, above.nextPosition(), name, label)
          open = new OpenTreeNode(line.depth, id, NodeKind.Scope, above.suite, above.scopeNames :+ name) :: open
        case WrittenNode.Test(name, label, writtenTestName) =>
          val testName = writtenTestName.getOrElse((above.scopeNames :+ name).mkString(" "))
          if (!testNames.add((above.suite.id, testName))) {
            fail(s"two tests of one suite have the test name \"$testName\": give '${line.text}' its own test name")
          }
          val id = above.suite.testIds.getOrElseUpdate(testName, minted())
          hold(id, NodeKind.Test, name, Some(above.id))
          built += StructureNode.Test(id, above.id, above.nextPosition(), name, label)
      }
    }
    built.result()
  }

  private def scalaTestEvent(line: NotationLine.Event): BuiltEvent = {
    val fields = new WrittenFields(line)
    val decoded = fields.decodedEvent
    val execution = line.node match {
      case None =>
        if (line.execution.nonEmpty || line.parentExecution.nonEmpty) {
          fail(s"an event assigned to the run has no execution: '${line.text}'")
        }
        None
      case Some(reference) =>
        val node = nodeNamed(reference)
        if (node.kind == NodeKind.Suite && line.parentExecution.nonEmpty) {
          fail(s"a suite's event has no parent execution: '${line.text}'")
        }
        val parentExecution = node.parent.filter(_ => node.kind != NodeKind.Suite).map { parent =>
          line.parentExecution.getOrElse(nodes(parent).latestOpenExecution.orElse(nodes(parent).latestExecution).getOrElse(1))
        }
        val number = line.execution.getOrElse(1)
        node.record(number, RunUnderConstruction.eventsEndingAnExecution.contains(line.className))
        Some(NodeExecution(node.id, number, parentExecution))
    }
    BuiltEvent(
      line.text,
      TestEventLogEvent.ScalaTestEvent(execution, decoded),
      FixedParts(line.fields.map { case (name, _) => name }.toSet, line.parentExecution.nonEmpty, fields.writtenThrowable)
    )
  }
}

private object RunUnderConstruction {

  // Only a suite or scope is a parent, so only their executions' ends are read.
  val eventsEndingAnExecution: Set[String] = Set("SuiteCompleted", "SuiteAborted", "ScopeClosed", "ScopePending")
}

private final class WrittenFields(line: NotationLine.Event) {

  private def fail(reason: String): Nothing = throw new NotationError(s"$reason: '${line.text}'")

  private val names = line.fields.map { case (name, _) => name }

  names.diff(names.distinct).headOption.foreach(repeated => fail(s"an event writes its $repeated once"))

  val writtenThrowable: Option[WrittenThrowable] = line.fields.collectFirst { case (_, FieldValue.Thrown(written)) => written }

  private def text(name: String): Option[String] = line.fields.collectFirst { case (`name`, FieldValue.Text(value)) => value }

  private def number(name: String): Option[Long] = line.fields.collectFirst { case (`name`, FieldValue.Number(value)) => value }

  def decodedEvent: DecodedEvent = {
    val throwable = writtenThrowable.map(WrittenFields.decodedThrowable)
    val message = text("message").getOrElse(throwable.flatMap(_.message).getOrElse(""))
    val analysis = line.fields.collectFirst { case (_, FieldValue.Texts(values)) => values }.getOrElse(Vector.empty)
    val duration = number("duration")
    val location = line.fields.collectFirst { case (_, FieldValue.Place(written)) => written }
    val threadName = text("threadName").getOrElse(TestRunBuilder.defaultThreadName)
    val timeStamp = number("timeStamp").getOrElse(TestRunBuilder.defaultTimeStamp)
    val decoded = line.className match {
      case "TestStarting" => DecodedEvent.TestStarting(location, threadName, timeStamp)
      case "TestSucceeded" => DecodedEvent.TestSucceeded(duration, location, threadName, timeStamp)
      case "TestFailed" => DecodedEvent.TestFailed(message, analysis, throwable, duration, location, threadName, timeStamp)
      case "TestIgnored" => DecodedEvent.TestIgnored(location, threadName, timeStamp)
      case "TestPending" => DecodedEvent.TestPending(duration, location, threadName, timeStamp)
      case "TestCanceled" => DecodedEvent.TestCanceled(message, throwable, duration, location, threadName, timeStamp)
      case "SuiteStarting" => DecodedEvent.SuiteStarting(location, threadName, timeStamp)
      case "SuiteCompleted" => DecodedEvent.SuiteCompleted(duration, location, threadName, timeStamp)
      case "SuiteAborted" => DecodedEvent.SuiteAborted(message, throwable, duration, location, threadName, timeStamp)
      case "RunStarting" => DecodedEvent.RunStarting(testCount, Map.empty, location, threadName, timeStamp)
      case "RunCompleted" => DecodedEvent.RunCompleted(duration, None, location, threadName, timeStamp)
      case "RunStopped" => DecodedEvent.RunStopped(duration, None, location, threadName, timeStamp)
      case "RunAborted" => DecodedEvent.RunAborted(message, throwable, duration, None, location, threadName, timeStamp)
      case "InfoProvided" => DecodedEvent.InfoProvided(message, throwable, location, threadName, timeStamp)
      case "AlertProvided" => DecodedEvent.AlertProvided(message, throwable, location, threadName, timeStamp)
      case "NoteProvided" => DecodedEvent.NoteProvided(message, throwable, location, threadName, timeStamp)
      case "MarkupProvided" => DecodedEvent.MarkupProvided(text("text").getOrElse(""), location, threadName, timeStamp)
      case "ScopeOpened" => DecodedEvent.ScopeOpened(location, threadName, timeStamp)
      case "ScopeClosed" => DecodedEvent.ScopeClosed(location, threadName, timeStamp)
      case "ScopePending" => DecodedEvent.ScopePending(location, threadName, timeStamp)
      case "DiscoveryStarting" => DecodedEvent.DiscoveryStarting(threadName, timeStamp)
      case "DiscoveryCompleted" => DecodedEvent.DiscoveryCompleted(duration, threadName, timeStamp)
      case other => fail(s"the test event log has no event class $other")
    }
    names.find(name => !decoded.productElementNames.contains(name)).foreach { name =>
      fail(s"a ${line.className} has no $name")
    }
    decoded
  }

  private def testCount: Int = {
    val written = number("testCount").getOrElse(0L)
    if (written < Int.MinValue || written > Int.MaxValue) {
      fail(s"the test count $written does not fit an Int")
    }
    written.toInt
  }
}

private object WrittenFields {

  // Recurses once for each cause and each nested list of suppressed throwables a test writes on one line.
  def decodedThrowable(written: WrittenThrowable): DecodedThrowable = {
    val message = written.message match {
      case WrittenMessage.Text(value) => Some(value)
      case WrittenMessage.Null | WrittenMessage.Unwritten => None
    }
    val stackDepth = written.stackDepth.map(depth => DecodedStackDepth(depth.fileName, depth.filePathname, depth.lineNumber))
    val frames = written.frames.getOrElse(Vector.empty).map(EventNotation.frame)
    val decoded = new DecodedThrowable(written.className, message, stackDepth, written.isPropertyCheckFailure, frames)
    decoded.link(written.cause.map(decodedThrowable), written.suppressed.getOrElse(Vector.empty).map(decodedThrowable))
    decoded
  }
}
