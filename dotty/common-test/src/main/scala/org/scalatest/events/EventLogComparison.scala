package org.scalatest.events

/**
 * Compares the events of a test event log with those a criterion gives, built by `TestRunBuilder`.
 *
 * Each event is compared only on what its criterion fixes: a `structure` event on its whole tree, and a ScalaTest
 * event on its node, its execution, its class and the fields and throwable parts it writes. The log's node IDs
 * need not be the built ones: each built node is taken to be the log's node at its place in a matching `structure`
 * event, and must stay that node in every later event.
 */
private[scalatest] object EventLogComparison {

  /** Returns why the log is not exactly the events expected, or `None` where it is. */
  def mismatchWhenIs(log: Seq[TestEventLogEvent], expected: Seq[BuiltEvent]): Option[String] = {
    val logged = log.toVector
    var nodes = MatchedNodes.none
    var mismatch = Option.empty[String]
    var index = 0
    while (mismatch.isEmpty && index < expected.size && index < logged.size) {
      matched(nodes, expected(index), logged(index)) match {
        case Right(withEvent) => nodes = withEvent
        case Left(reason) => mismatch = Some(s"event ${index + 1} of the log is not '${expected(index).notation}': $reason")
      }
      index += 1
    }
    mismatch.orElse {
      if (logged.size == expected.size) None else Some(s"the log holds ${logged.size} events, not the ${expected.size} expected")
    }
  }

  /**
   * Returns why the log does not hold the events expected, in that order, with any others between them, or `None`
   * where it does. Each event expected is taken to be the first event of the log, after the one before it, that it
   * matches.
   */
  def mismatchWhenHoldsInOrder(log: Seq[TestEventLogEvent], expected: Seq[BuiltEvent]): Option[String] = {
    val logged = log.toVector
    var nodes = MatchedNodes.none
    var mismatch = Option.empty[String]
    var next = 0
    val remaining = expected.iterator
    while (mismatch.isEmpty && remaining.hasNext) {
      val built = remaining.next()
      var found = false
      while (!found && next < logged.size) {
        matched(nodes, built, logged(next)).foreach { withEvent =>
          nodes = withEvent
          found = true
        }
        next += 1
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

  private def matched(nodes: MatchedNodes, built: BuiltEvent, logged: TestEventLogEvent): Either[String, MatchedNodes] = {
    (built.event, logged) match {
      case (_: TestEventLogEvent.RunStarted, _: TestEventLogEvent.RunStarted) => Right(nodes)
      case (TestEventLogEvent.RunEnded(expected), TestEventLogEvent.RunEnded(outcome)) =>
        if (expected == outcome) Right(nodes) else Left(s"its outcome is $outcome, not $expected")
      case (TestEventLogEvent.Structure(expected), TestEventLogEvent.Structure(tree)) => matchedTree(nodes, expected, tree)
      case (TestEventLogEvent.ScalaTestEvent(expectedExecution, expected), TestEventLogEvent.ScalaTestEvent(execution, event)) =>
        scalaTestEventMismatch(nodes, built.fixed, expectedExecution, expected, execution, event).toLeft(nodes)
      case _ => Left(s"it is a ${describe(logged)}")
    }
  }

  private def describe(logged: TestEventLogEvent): String = {
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
            Left(s"its node ${index + 1} is ${withoutIds(node)}, not ${withoutIds(expectedNode)}")
          } else {
            val place = s"its node ${index + 1}"
            soFar.matching(idOf(expectedNode), idOf(node)).toRight(s"$place is not the node an earlier structure held there").flatMap { withNode =>
              (parentOf(expectedNode), parentOf(node)) match {
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

  private def idOf(node: StructureNode): NodeId = {
    node match {
      case suite: StructureNode.Suite => suite.id
      case scope: StructureNode.Scope => scope.id
      case test: StructureNode.Test => test.id
    }
  }

  private def parentOf(node: StructureNode): Option[NodeId] = {
    node match {
      case suite: StructureNode.Suite => suite.parent
      case scope: StructureNode.Scope => Some(scope.parent)
      case test: StructureNode.Test => Some(test.parent)
    }
  }

  private def withoutIds(node: StructureNode): String = {
    def quoted(text: String): String = "\"" + text + "\""
    node match {
      case StructureNode.Suite(_, _, position, name, suiteId, className) =>
        s"suite ${quoted(name)}, class ${quoted(className)}, id ${quoted(suiteId)}, ${position.fold("no position")("position " + _)}"
      case StructureNode.Scope(_, _, position, name, label) => s"scope ${quoted(name)}, label ${quoted(label)}, position $position"
      case StructureNode.Test(_, _, position, name, label) => s"test ${quoted(name)}, label ${quoted(label)}, position $position"
    }
  }

  private def scalaTestEventMismatch(
    nodes: MatchedNodes,
    fixed: FixedParts,
    expectedExecution: Option[NodeExecution],
    expected: DecodedEvent,
    execution: Option[NodeExecution],
    event: DecodedEvent
  ): Option[String] = {
    if (expected.productPrefix != event.productPrefix) {
      Some(s"it is a ${event.productPrefix}")
    } else {
      executionMismatch(nodes, fixed, expectedExecution, execution).orElse {
        val expectedFields = fieldsOf(expected)
        val fields = fieldsOf(event)
        fixed.fields.toVector.sorted.iterator.map { name =>
          if (name == "throwable") {
            throwableMismatch(fixed.throwable, fields(name))
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
    fixed: FixedParts,
    expected: Option[NodeExecution],
    execution: Option[NodeExecution]
  ): Option[String] = {
    (expected, execution) match {
      case (None, None) => None
      case (None, Some(_)) => Some("it is assigned to a node, not to the run")
      case (Some(_), None) => Some("it is assigned to the run, not to a node")
      case (Some(expectedExecution), Some(loggedExecution)) =>
        if (!nodes.loggedNode(expectedExecution.node).contains(loggedExecution.node)) {
          Some(s"it is assigned to another node, ${loggedExecution.node.value}")
        } else if (expectedExecution.execution != loggedExecution.execution) {
          Some(s"it is of execution ${loggedExecution.execution}, not ${expectedExecution.execution}")
        } else if (fixed.parentExecution && expectedExecution.parentExecution != loggedExecution.parentExecution) {
          def number(parentExecution: Option[Int]): String = parentExecution.fold("absent")(_.toString)
          Some(s"its parent execution is ${number(loggedExecution.parentExecution)}, not ${number(expectedExecution.parentExecution)}")
        } else {
          None
        }
    }
  }

  private def throwableMismatch(expected: Option[WrittenThrowable], logged: Any): Option[String] = {
    (expected, logged) match {
      case (Some(written), Some(throwable: DecodedThrowable)) => writtenPartsMismatch(written, throwable, "its throwable")
      case _ => Some("it has no throwable")
    }
  }

  // Recurses once for each cause and each nested list of suppressed throwables the criterion writes, never along
  // the logged throwable's own links, which can form a cycle.
  private def writtenPartsMismatch(written: WrittenThrowable, logged: DecodedThrowable, name: String): Option[String] = {
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

    def suppressed: Option[String] = {
      written.suppressed.flatMap { expected =>
        if (expected.size != logged.suppressed.size) {
          Some(s"$name suppresses ${logged.suppressed.size} throwables, not ${expected.size}")
        } else {
          expected.zip(logged.suppressed).zipWithIndex.iterator.map { case ((writtenSuppressed, loggedSuppressed), index) =>
            writtenPartsMismatch(writtenSuppressed, loggedSuppressed, s"$name's suppressed throwable ${index + 1}")
          }.collectFirst { case Some(mismatch) => mismatch }
        }
      }
    }

    def cause: Option[String] = {
      written.cause.flatMap { expected =>
        logged.cause match {
          case None => Some(s"$name has no cause")
          case Some(found) => writtenPartsMismatch(expected, found, s"$name's cause")
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
