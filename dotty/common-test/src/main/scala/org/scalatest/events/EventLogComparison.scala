package org.scalatest.events

import NotationText.quoted

private[scalatest] object EventLogComparison {

  def mismatchWhenIs(log: Seq[TestEventLogEvent], expected: Seq[BuiltEvent]): Option[String] = {
    val logged = log.toVector
    var nodes = MatchedNodes.none
    var latestNodes = LatestNodes.none
    var mismatch = Option.empty[String]
    var index = 0
    while (mismatch.isEmpty && index < expected.size && index < logged.size) {
      matched(nodes, latestNodes, expected(index), logged(index)) match {
        case Right(withEvent) => nodes = withEvent
        case Left(reason) => mismatch = Some(s"event ${index + 1} of the log is not '${expected(index).notation}': $reason")
      }
      latestNodes = latestNodes.afterBuilt(expected(index).event).afterLogged(logged(index))
      index += 1
    }
    mismatch.orElse {
      if (logged.size == expected.size) None else Some(s"the log holds ${logged.size} events, not the ${expected.size} expected")
    }
  }

  def mismatchWhenHoldsInOrder(log: Seq[TestEventLogEvent], expected: Seq[BuiltEvent]): Option[String] = {
    val logged = log.toVector
    var nodes = MatchedNodes.none
    var latestNodes = LatestNodes.none
    var mismatch = Option.empty[String]
    var next = 0
    val remaining = expected.iterator
    while (mismatch.isEmpty && remaining.hasNext) {
      val built = remaining.next()
      var found = false
      while (!found && next < logged.size) {
        matched(nodes, latestNodes, built, logged(next)).foreach { withEvent =>
          nodes = withEvent
          found = true
        }
        latestNodes = latestNodes.afterLogged(logged(next))
        next += 1
      }
      if (found) {
        latestNodes = latestNodes.afterBuilt(built.event)
      }
      if (!found) {
        mismatch = Some(s"the log does not hold '${built.notation}' after the events expected before it")
      }
    }
    mismatch
  }

  private final case class MatchedNodes(loggedByBuilt: Map[NodeId, NodeId], builtByLogged: Map[NodeId, NodeId]) {

    def loggedNode(built: NodeId): Option[NodeId] = loggedByBuilt.get(built)

    def matching(built: NodeId, logged: NodeId): Option[MatchedNodes] = {
      (loggedByBuilt.get(built), builtByLogged.get(logged)) match {
        case (None, None) => Some(MatchedNodes(loggedByBuilt.updated(built, logged), builtByLogged.updated(logged, built)))
        case (Some(`logged`), _) => Some(this)
        case _ => None
      }
    }
  }

  private object MatchedNodes {
    val none: MatchedNodes = MatchedNodes(Map.empty, Map.empty)
  }

  private final case class LatestNodes(built: Map[NodeId, StructureNode], logged: Map[NodeId, StructureNode]) {

    def afterBuilt(event: TestEventLogEvent): LatestNodes = copy(built = withNodesOf(built, event))

    def afterLogged(event: TestEventLogEvent): LatestNodes = copy(logged = withNodesOf(logged, event))
  }

  private object LatestNodes {
    val none: LatestNodes = LatestNodes(Map.empty, Map.empty)
  }

  private def withNodesOf(nodes: Map[NodeId, StructureNode], event: TestEventLogEvent): Map[NodeId, StructureNode] = {
    event match {
      case TestEventLogEvent.Structure(tree) => nodes ++ tree.map(node => node.id -> node)
      case _ => nodes
    }
  }

  private def ancestorsTopDown(node: NodeId, nodes: Map[NodeId, StructureNode]): Vector[NodeId] = {
    def parentOfNode(id: NodeId): Option[NodeId] = nodes.get(id).flatMap(_.parentId)
    Iterator.unfold(parentOfNode(node))(_.map(parent => (parent, parentOfNode(parent)))).toVector.reverse
  }

  private def matched(nodes: MatchedNodes, latestNodes: LatestNodes, built: BuiltEvent, logged: TestEventLogEvent): Either[String, MatchedNodes] = {
    (built.event, logged) match {
      case (_: TestEventLogEvent.RunStarted, _: TestEventLogEvent.RunStarted) => Right(nodes)
      case (TestEventLogEvent.RunEnded(expected), TestEventLogEvent.RunEnded(outcome)) =>
        if (expected == outcome) Right(nodes) else Left(s"its outcome is $outcome, not $expected")
      case (TestEventLogEvent.Structure(expected), TestEventLogEvent.Structure(tree)) => matchedTree(nodes, expected, tree)
      case (TestEventLogEvent.ScalaTestEvent(expectedExecution, expected), TestEventLogEvent.ScalaTestEvent(execution, event)) =>
        scalaTestEventMismatch(nodes, latestNodes, built.written, expectedExecution, expected, execution, event).toLeft(nodes)
      case _ => Left(s"it is a ${eventKind(logged)}")
    }
  }

  private def eventKind(logged: TestEventLogEvent): String = {
    logged match {
      case _: TestEventLogEvent.RunStarted => "runStarted event"
      case _: TestEventLogEvent.Structure => "structure event"
      case TestEventLogEvent.ScalaTestEvent(_, event) => event.productPrefix
      case _: TestEventLogEvent.RunEnded => "runEnded event"
    }
  }

  private def matchedTree(
    nodes: MatchedNodes,
    expected: IndexedSeq[StructureNode],
    tree: IndexedSeq[StructureNode]
  ): Either[String, MatchedNodes] = {
    if (expected.size != tree.size) {
      Left(s"it holds ${tree.size} nodes, not ${expected.size}")
    } else {
      expected.zip(tree).zipWithIndex.foldLeft[Either[String, MatchedNodes]](Right(nodes)) {
        case (Right(soFar), ((expectedNode, node), index)) =>
          if (withoutIds(expectedNode) != withoutIds(node)) {
            Left(s"its node ${index + 1} is ${inNotation(node)}, not ${inNotation(expectedNode)}")
          } else {
            val place = s"its node ${index + 1}"
            soFar.matching(expectedNode.id, node.id).toRight(s"$place is not the node an earlier structure held there").flatMap { withNode =>
              (expectedNode.parentId, node.parentId) match {
                case (Some(expectedParent), Some(parent)) =>
                  withNode.matching(expectedParent, parent).toRight(s"$place is under another parent")
                case (None, None) => Right(withNode)
                case (None, Some(_)) => Left(s"$place has a parent, where none is expected")
                case (Some(_), None) => Left(s"$place has no parent, where one is expected")
              }
            }
          }
        case (mismatch, _) => mismatch
      }
    }
  }

  private def withoutIds(node: StructureNode): StructureNode = {
    val noNode = NodeId(Vector.empty)
    node match {
      case suite: StructureNode.Suite => suite.copy(id = noNode, parent = None)
      case scope: StructureNode.Scope => scope.copy(id = noNode, parent = noNode)
      case test: StructureNode.Test => test.copy(id = noNode, parent = noNode)
    }
  }

  private def inNotation(node: StructureNode): String = {
    node match {
      case StructureNode.Suite(_, _, position, name, suiteId, className) =>
        s"suite ${quoted(name)}, class ${quoted(className)}, id ${quoted(suiteId.value)}, ${position.fold("no position")("position " + _)}"
      case StructureNode.Scope(_, _, position, name, label) => s"scope ${quoted(name)}, label ${quoted(label)}, position $position"
      case StructureNode.Test(_, _, position, name, label, ignored) =>
        s"test ${quoted(name)}, label ${quoted(label)}, position $position${if (ignored) ", ignored" else ""}"
    }
  }

  private def scalaTestEventMismatch(
    nodes: MatchedNodes,
    latestNodes: LatestNodes,
    written: WrittenParts,
    expectedExecution: Option[NodeExecution],
    expected: DecodedEvent,
    execution: Option[NodeExecution],
    event: DecodedEvent
  ): Option[String] = {
    if (expected.productPrefix != event.productPrefix) {
      Some(s"it is a ${event.productPrefix}")
    } else {
      executionMismatch(nodes, latestNodes, written, expectedExecution, execution).orElse {
        val expectedFields = fieldsOf(expected)
        val fields = fieldsOf(event)
        written.fields.toVector.sorted.iterator.map { name =>
          if (name == "throwable") {
            throwableMismatch(written.throwable, fields(name))
          } else if (expectedFields(name) == fields(name)) {
            None
          } else {
            Some(s"its $name is ${fields(name)}, not ${expectedFields(name)}")
          }
        }.collectFirst { case Some(mismatch) => mismatch }
      }
    }
  }

  private def fieldsOf(event: DecodedEvent): Map[String, Any] = event.productElementNames.zip(event.productIterator).toMap

  private def executionMismatch(
    nodes: MatchedNodes,
    latestNodes: LatestNodes,
    written: WrittenParts,
    expected: Option[NodeExecution],
    execution: Option[NodeExecution]
  ): Option[String] = {
    (expected, execution) match {
      case (None, None) => None
      case (None, Some(_)) => Some("it is assigned to a node, not to the run")
      case (Some(_), None) => Some("it is assigned to the run, not to a node")
      case (Some(expectedExecution), Some(loggedExecution)) =>
        if (!nodes.loggedNode(expectedExecution.node).contains(loggedExecution.node)) {
          Some(s"it is assigned to another node, ${nodeDescription(loggedExecution.node, latestNodes.logged)}")
        } else if (expectedExecution.executionIndex != loggedExecution.executionIndex) {
          Some(s"it is of execution ${loggedExecution.executionIndex}, not ${expectedExecution.executionIndex}")
        } else {
          markedAncestorExecutionMismatch(nodes, latestNodes, written, expectedExecution, loggedExecution)
        }
    }
  }

  private def markedAncestorExecutionMismatch(
    nodes: MatchedNodes,
    latestNodes: LatestNodes,
    written: WrittenParts,
    expected: NodeExecution,
    logged: NodeExecution
  ): Option[String] = {
    // From the log's own structures, not the built ones: a further structure in the log can move the node.
    val loggedAncestors = ancestorsTopDown(logged.node, latestNodes.logged)
    ancestorsTopDown(expected.node, latestNodes.built).zip(expected.executionPath).iterator.collect {
      case (ancestor, expectedExecution) if written.markedAncestors.contains(ancestor) =>
        val name = nodeDescription(ancestor, latestNodes.built)
        nodes.loggedNode(ancestor).map(loggedAncestors.indexOf).filter(_ >= 0) match {
          case None => Some(s"it is not under $name")
          case Some(depth) =>
            logged.executionPath.lift(depth) match {
              case Some(`expectedExecution`) => None
              case Some(loggedExecution) => Some(s"it ran in execution $loggedExecution of $name, not $expectedExecution")
              case None => Some(s"its execution path has no execution of $name")
            }
        }
    }.collectFirst { case Some(mismatch) => mismatch }
  }

  private def nodeDescription(node: NodeId, nodes: Map[NodeId, StructureNode]): String = {
    nodes.get(node) match {
      case Some(suite: StructureNode.Suite) => s"suite ${quoted(suite.name)}"
      case Some(scope: StructureNode.Scope) => s"scope ${quoted(scope.name)}"
      case Some(test: StructureNode.Test) => s"test ${quoted(test.name)}"
      case None => "a node no structure holds"
    }
  }

  private def throwableMismatch(expected: Option[WrittenThrowable], logged: Any): Option[String] = {
    (expected, logged) match {
      case (Some(written), Some(throwable: DecodedThrowable)) => writtenThrowableMismatch(written, throwable, "its throwable")
      case _ => Some("it has no throwable")
    }
  }

  private def writtenThrowableMismatch(written: WrittenThrowable, logged: DecodedThrowable, name: String): Option[String] = {
    def differing[A](part: String, expected: A, found: A): Option[String] =
      if (expected == found) None else Some(s"$name's $part is $found, not $expected")

    def message: Option[String] = {
      written.message match {
        case WrittenMessage.Unwritten => None
        case WrittenMessage.Null => differing("message", Option.empty[String], logged.message)
        case WrittenMessage.Text(value) => differing("message", Option(value), logged.message)
      }
    }

    def stackDepth: Option[String] = {
      written.stackDepth.flatMap { depth =>
        logged.stackDepth match {
          case None => Some(s"$name is not a StackDepth")
          case Some(found) =>
            differing("file name", depth.fileName, found.fileName)
              .orElse(differing("line number", depth.lineNumber, found.lineNumber))
              .orElse(depth.filePathname.flatMap(pathname => differing("file pathname", Option(pathname), found.filePathname)))
        }
      }
    }

    def propertyCheck: Option[String] =
      if (written.isPropertyCheckFailure && !logged.isPropertyCheckFailure) Some(s"$name is not a property check failure") else None

    def frames: Option[String] =
      written.frames.flatMap(texts => differing("stack trace", texts.map(EventNotation.frame), logged.stackTrace.toVector))

    // suppressed and cause recurse on what the criterion writes,
    // never on the logged throwable's links, which can cycle.
    def suppressed: Option[String] = {
      written.suppressed.flatMap { expected =>
        if (expected.size != logged.suppressed.size) {
          Some(s"$name suppresses ${logged.suppressed.size} throwables, not ${expected.size}")
        } else {
          expected.zip(logged.suppressed).zipWithIndex.iterator.map { case ((writtenSuppressed, loggedSuppressed), index) =>
            writtenThrowableMismatch(writtenSuppressed, loggedSuppressed, s"$name's suppressed throwable ${index + 1}")
          }.collectFirst { case Some(mismatch) => mismatch }
        }
      }
    }

    def cause: Option[String] = {
      written.cause.flatMap { expected =>
        logged.cause match {
          case None => Some(s"$name has no cause")
          case Some(found) => writtenThrowableMismatch(expected, found, s"$name's cause")
        }
      }
    }

    differing("class", written.className, logged.className)
      .orElse(message)
      .orElse(stackDepth)
      .orElse(propertyCheck)
      .orElse(frames)
      .orElse(suppressed)
      .orElse(cause)
  }
}
