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

private[scalatest] final case class WrittenParts(fields: Set[String], markedAncestors: Set[NodeId], throwable: Option[WrittenThrowable])

private[scalatest] object WrittenParts {
  val none: WrittenParts = WrittenParts(Set.empty, Set.empty, None)
}

private[scalatest] final case class BuiltEvent(notation: String, event: TestEventLogEvent, written: WrittenParts)

private[scalatest] enum BuiltRunItem {
  case Event(built: BuiltEvent)
  case CapturedLine(stream: CapturedStream, line: String)
}

private[scalatest] final case class BuiltRun(items: Vector[BuiltRunItem]) {

  def events: Vector[BuiltEvent] = items.collect { case BuiltRunItem.Event(built) => built }
}

private[scalatest] object TestRunBuilder {

  val defaultPid: Long = 1L
  val defaultStartTime: String = "1970-01-01T00:00:00Z"
  val defaultThreadName: String = "main"
  val defaultTimeStamp: Long = 0L

  def build(parts: TestRunPart*): BuiltRun = {
    val run = new RunUnderConstruction
    BuiltRun(
      parts.toVector.flatMap {
        case TestRunPart.Events(notation) => EventNotation.parse(notation).map(line => BuiltRunItem.Event(run.built(line)))
        case TestRunPart.CapturedLine(stream, line) => Vector(BuiltRunItem.CapturedLine(stream, line))
      }
    )
  }
}

private final class HeldNode(val id: NodeId, val kind: NodeKind, firstName: String, firstParent: Option[NodeId]) {

  private var latestName = firstName
  private var latestParent = firstParent
  private var executions = Set.empty[Int]
  private var endedExecutions = Set.empty[Int]

  def name: String = latestName

  def parent: Option[NodeId] = latestParent

  def heldAgain(name: String, parent: Option[NodeId]): Unit = {
    latestName = name
    latestParent = parent
  }

  def record(execution: Int, endsIt: Boolean): Unit = {
    executions += execution
    if (endsIt) {
      endedExecutions += execution
    }
  }

  def defaultExecution: Int = (executions -- endedExecutions).maxOption.orElse(executions.maxOption).getOrElse(1)
}

private final class HeldSuite(val id: NodeId, val suiteId: SuiteId, val className: TestClassName, val parent: Option[NodeId], val position: Option[Int]) {

  private var positionOfFirstScopeOrTest = 0

  def firstPositionOfScopesAndTests: Int = positionOfFirstScopeOrTest

  def placeScopesAndTestsFrom(position: Int): Unit = positionOfFirstScopeOrTest = position
}

private final class OpenTreeNode(
  val depth: Int,
  val id: NodeId,
  val kind: NodeKind,
  val suite: NodeId,
  val scopeNames: Vector[String],
  firstPosition: Int = 0
) {

  private var positionOfNextChild = firstPosition
  private var positionOfFirstScopeOrTest = Option.empty[Int]
  private val scopesNamed = mutable.Map.empty[String, Int]

  def nextPosition(): Int = {
    positionOfNextChild += 1
    positionOfNextChild - 1
  }

  def nextScopeOrTestPosition(): Int = {
    val position = nextPosition()
    positionOfFirstScopeOrTest = positionOfFirstScopeOrTest.orElse(Some(position))
    position
  }

  def firstPositionOfScopesAndTests: Int = positionOfFirstScopeOrTest.getOrElse(positionOfNextChild)

  def nextIndexOfScopeNamed(name: String): Int = {
    val index = scopesNamed.getOrElse(name, 0)
    scopesNamed(name) = index + 1
    index
  }
}

private final class RunUnderConstruction {

  private val suitesUnder = mutable.Map.empty[Option[NodeId], mutable.ArrayBuffer[HeldSuite]]
  private val nodesInOrderFirstHeld = mutable.ArrayBuffer.empty[HeldNode]
  private val nodes = mutable.Map.empty[NodeId, HeldNode]

  def built(line: NotationLine): BuiltEvent = {
    line match {
      case NotationLine.RunStarted(text) =>
        BuiltEvent(text, TestEventLogEvent.RunStarted(TestRunBuilder.defaultPid, TestRunBuilder.defaultStartTime), WrittenParts.none)
      case block: NotationLine.Structure =>
        BuiltEvent(block.text, TestEventLogEvent.Structure(structureNodes(block)), WrittenParts.none)
      case event: NotationLine.Event => scalaTestEvent(event)
      case NotationLine.RunEnded(outcome, text) => BuiltEvent(text, TestEventLogEvent.RunEnded(outcome), WrittenParts.none)
    }
  }

  private def fail(reason: String): Nothing = throw new NotationError(reason)

  private def hold(id: NodeId, kind: NodeKind, name: String, parent: Option[NodeId]): Unit = {
    nodes.get(id) match {
      case Some(held) => held.heldAgain(name, parent)
      case None => {
        val held = new HeldNode(id, kind, name, parent)
        nodes(id) = held
        nodesInOrderFirstHeld += held
      }
    }
  }

  private def sharingName(kind: NodeKind, name: String): Vector[HeldNode] =
    nodesInOrderFirstHeld.filter(held => held.kind == kind && held.name == name).toVector

  private def nodeNamed(reference: NodeReference): HeldNode = {
    val named = sharingName(reference.kind, reference.name)
    reference.ordinal match {
      case Some(ordinal) if ordinal <= named.size => named(ordinal - 1)
      case Some(_) => fail(s"no structure so far holds ${reference.text}: ${named.size} share that name")
      case None if named.size == 1 => named.head
      case None if named.isEmpty => fail(s"no structure so far holds ${reference.text}")
      case None => fail(s"the structures so far hold ${named.size} nodes named ${reference.text}: add #<n>")
    }
  }

  private def suitesUnderParent(parent: Option[NodeId]): mutable.ArrayBuffer[HeldSuite] =
    suitesUnder.getOrElseUpdate(parent, mutable.ArrayBuffer.empty)

  private def newSuite(written: WrittenNode.Suite, parent: Option[NodeId], position: Option[Int], text: String): HeldSuite = {
    val ordinalItTakes = sharingName(NodeKind.Suite, written.name).size + 1
    written.ordinalAmongSameName.filter(_ != ordinalItTakes).foreach { ordinal =>
      fail(s"a suite new to the event log is #$ordinalItTakes among the suites that share its name, not #$ordinal: '$text'")
    }
    val siblings = suitesUnderParent(parent)
    val index = siblings.count(_.suiteId == written.suiteId)
    val id = parent.fold(NodeId.topLevelSuite(written.suiteId, index))(NodeId.nestedSuite(_, written.suiteId, index))
    val added = new HeldSuite(id, written.suiteId, written.className, parent, position)
    siblings += added
    hold(added.id, NodeKind.Suite, written.name, parent)
    added
  }

  private final case class StructureRoot(suite: HeldSuite, isFurther: Boolean)

  private def structureRootOf(block: NotationLine.Structure): StructureRoot = {
    val (written, text) = block.tree.headOption match {
      case Some(TreeLine(1, suite: WrittenNode.Suite, text)) => (suite, text)
      case _ => fail(s"a structure starts with its suite, indented 2 spaces:\n${block.text}")
    }
    val parent = block.under.map(reference => nodeNamed(reference).id)
    val siblings = suitesUnderParent(parent)
    val earlier = written.ordinalAmongSameName match {
      case Some(ordinal) =>
        sharingName(NodeKind.Suite, written.name).lift(ordinal - 1).map { named =>
          siblings.find(sibling => sibling.id == named.id && sibling.suiteId == written.suiteId).getOrElse {
            fail(s"suite \"${written.name}\" #$ordinal is not a suite of suite ID ${written.suiteId.value} under the structure's parent: '$text'")
          }
        }
      case None => siblings.find(_.suiteId == written.suiteId)
    }
    earlier match {
      case Some(suite) => {
        if (suite.className != written.className) {
          fail(s"a further structure keeps its suite's class, ${suite.className.value}:\n${block.text}")
        }
        block.tree.tail.collectFirst { case TreeLine(_, _: WrittenNode.Suite, nested) => nested }.foreach { nested =>
          fail(s"a further structure holds its suite's scopes and tests only, not '$nested'")
        }
        hold(suite.id, NodeKind.Suite, written.name, suite.parent)
        StructureRoot(suite, isFurther = true)
      }
      case None => StructureRoot(newSuite(written, parent, None, text), isFurther = false)
    }
  }

  private def structureNodes(block: NotationLine.Structure): Vector[StructureNode] = {
    val structureRoot = structureRootOf(block)
    val root = structureRoot.suite
    val built = Vector.newBuilder[StructureNode]
    built += StructureNode.Suite(root.id, root.parent, root.position, nodes(root.id).name, root.suiteId, root.className)
    val testNames = mutable.Set.empty[(NodeId, TestName)]
    val openRoot = new OpenTreeNode(1, root.id, NodeKind.Suite, root.id, Vector.empty, if (structureRoot.isFurther) root.firstPositionOfScopesAndTests else 0)
    val suitesFirstHeld = mutable.ArrayBuffer.empty[(HeldSuite, OpenTreeNode)]
    if (!structureRoot.isFurther) {
      suitesFirstHeld += root -> openRoot
    }
    var open = List(openRoot)
    block.tree.tail.foreach { line =>
      open = open.dropWhile(_.depth >= line.depth)
      val above = open.headOption.filter(_.depth == line.depth - 1).getOrElse {
        fail(s"'${line.text}' is not indented 2 spaces under a suite or scope of its structure")
      }
      line.node match {
        case written: WrittenNode.Suite => {
          if (above.kind != NodeKind.Suite) {
            fail(s"a suite is nested in a suite, not in a scope: '${line.text}'")
          }
          val nested = newSuite(written, Some(above.id), Some(above.nextPosition()), line.text)
          built += StructureNode.Suite(nested.id, nested.parent, nested.position, written.name, nested.suiteId, nested.className)
          val openNested = new OpenTreeNode(line.depth, nested.id, NodeKind.Suite, nested.id, Vector.empty)
          suitesFirstHeld += nested -> openNested
          open = openNested :: open
        }
        case WrittenNode.Scope(name, label) => {
          val id = NodeId.scope(above.id, name, above.nextIndexOfScopeNamed(name))
          hold(id, NodeKind.Scope, name, Some(above.id))
          built += StructureNode.Scope(id, above.id, above.nextScopeOrTestPosition(), name, label)
          open = new OpenTreeNode(line.depth, id, NodeKind.Scope, above.suite, above.scopeNames :+ name) :: open
        }
        case WrittenNode.Test(name, label, writtenTestName, ignored) => {
          val testName = TestName(writtenTestName.getOrElse((above.scopeNames :+ name).mkString(" ")))
          if (!testNames.add((above.suite, testName))) {
            fail(s"two tests of one suite have the test name \"${testName.value}\": give '${line.text}' its own test name")
          }
          val id = NodeId.test(above.suite, testName)
          hold(id, NodeKind.Test, name, Some(above.id))
          built += StructureNode.Test(id, above.id, above.nextScopeOrTestPosition(), name, label, ignored)
        }
      }
    }
    suitesFirstHeld.foreach { (suite, openSuite) => suite.placeScopesAndTestsFrom(openSuite.firstPositionOfScopesAndTests) }
    built.result()
  }

  private def ancestorsTopDown(node: HeldNode): Vector[HeldNode] =
    Iterator.unfold(node.parent)(parent => parent.map(nodes).map(held => (held, held.parent))).toVector.reverse

  private def scalaTestEvent(line: NotationLine.Event): BuiltEvent = {
    val fields = new WrittenFields(line)
    val decoded = fields.decodedEvent
    val (execution, markedAncestors) = line.node match {
      case None => {
        if (line.execution.nonEmpty || line.ancestorExecutions.nonEmpty) {
          fail(s"an event assigned to the run has no execution: '${line.text}'")
        }
        (None, Set.empty[NodeId])
      }
      case Some(reference) => {
        val node = nodeNamed(reference)
        val ancestors = ancestorsTopDown(node)
        val marked = line.ancestorExecutions.map { marker =>
          val ancestor = nodeNamed(marker.ancestor)
          if (!ancestors.contains(ancestor)) {
            fail(s"${marker.ancestor.text} is not above ${reference.text}: '${line.text}'")
          }
          ancestor -> marker.execution
        }
        val depths = marked.map { case (ancestor, _) => ancestors.indexOf(ancestor) }
        if (depths != depths.distinct.sorted) {
          fail(s"an event marks each ancestor's execution once, from the top-level suite down: '${line.text}'")
        }
        val markedExecutions = marked.map { case (ancestor, ancestorExecution) => ancestor.id -> ancestorExecution }.toMap
        val executionPath = ancestors.map(ancestor => markedExecutions.getOrElse(ancestor.id, ancestor.defaultExecution))
        val ownExecution = line.execution.getOrElse(1)
        marked.foreach { case (ancestor, ancestorExecution) => ancestor.record(ancestorExecution, endsIt = false) }
        node.record(ownExecution, RunUnderConstruction.eventsEndingAnExecution.contains(line.className))
        (Some(NodeExecution(node.id, executionPath :+ ownExecution)), markedExecutions.keySet)
      }
    }
    BuiltEvent(
      line.text,
      TestEventLogEvent.ScalaTestEvent(execution, decoded),
      WrittenParts(line.fields.map { case (name, _) => name }.toSet, markedAncestors, fields.writtenThrowable)
    )
  }
}

private object RunUnderConstruction {

  val eventsEndingAnExecution: Set[String] = Set(
    "SuiteCompleted",
    "SuiteAborted",
    "ScopeClosed",
    "ScopePending",
    "TestSucceeded",
    "TestFailed",
    "TestCanceled",
    "TestPending",
    "TestIgnored"
  )
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

  def decodedThrowable(written: WrittenThrowable): DecodedThrowable = {
    val message = written.message match {
      case WrittenMessage.Text(value) => Some(value)
      case WrittenMessage.Null | WrittenMessage.Unwritten => None
    }
    val stackDepth = written.stackDepth.map(depth => DecodedStackDepth(depth.fileName, depth.filePathname, depth.lineNumber))
    val frames = written.frames.getOrElse(Vector.empty).map(EventNotation.frame)
    val cause = written.cause.map(decodedThrowable)
    val suppressed = written.suppressed.getOrElse(Vector.empty).map(decodedThrowable)
    new DecodedThrowable(written.className, message, stackDepth, written.isPropertyCheckFailure, frames, cause, suppressed)
  }
}
