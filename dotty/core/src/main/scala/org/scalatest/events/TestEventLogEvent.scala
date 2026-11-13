package org.scalatest.events

private[scalatest] opaque type NodeId = Long

private[scalatest] object NodeId {

  def apply(value: Long): NodeId = value

  extension (id: NodeId) {
    def value: Long = id
  }
}

private[scalatest] enum StructureNode {
  case Suite(id: NodeId, parent: Option[NodeId], position: Option[Int], name: String, suiteId: String, className: String)
  case Scope(id: NodeId, parent: NodeId, position: Int, name: String, label: String)
  case Test(id: NodeId, parent: NodeId, position: Int, name: String, label: String)
}

private[scalatest] final case class NodeExecution(node: NodeId, execution: Int, parentExecution: Option[Int])

private[scalatest] enum RunOutcome {
  case Completed
  case Aborted
  case Stopped
}

private[scalatest] enum TestEventLogEvent {
  case RunStarted(pid: Long, startTime: String)
  case Structure(nodes: IndexedSeq[StructureNode])
  case ScalaTestEvent(execution: Option[NodeExecution], event: DecodedEvent)
  case RunEnded(outcome: RunOutcome)
}

private[scalatest] enum DecodedEvent {
  case TestStarting(location: Option[Location], threadName: String, timeStamp: Long)
  case TestSucceeded(duration: Option[Long], location: Option[Location], threadName: String, timeStamp: Long)
  case TestFailed(
    message: String,
    analysis: IndexedSeq[String],
    throwable: Option[DecodedThrowable],
    duration: Option[Long],
    location: Option[Location],
    threadName: String,
    timeStamp: Long
  )
  case TestIgnored(location: Option[Location], threadName: String, timeStamp: Long)
  case TestPending(duration: Option[Long], location: Option[Location], threadName: String, timeStamp: Long)
  case TestCanceled(
    message: String,
    throwable: Option[DecodedThrowable],
    duration: Option[Long],
    location: Option[Location],
    threadName: String,
    timeStamp: Long
  )
  case SuiteStarting(location: Option[Location], threadName: String, timeStamp: Long)
  case SuiteCompleted(duration: Option[Long], location: Option[Location], threadName: String, timeStamp: Long)
  case SuiteAborted(
    message: String,
    throwable: Option[DecodedThrowable],
    duration: Option[Long],
    location: Option[Location],
    threadName: String,
    timeStamp: Long
  )
  case RunStarting(
    testCount: Int,
    configMap: Map[String, String],
    location: Option[Location],
    threadName: String,
    timeStamp: Long
  )
  case RunCompleted(
    duration: Option[Long],
    summary: Option[Summary],
    location: Option[Location],
    threadName: String,
    timeStamp: Long
  )
  case RunStopped(
    duration: Option[Long],
    summary: Option[Summary],
    location: Option[Location],
    threadName: String,
    timeStamp: Long
  )
  case RunAborted(
    message: String,
    throwable: Option[DecodedThrowable],
    duration: Option[Long],
    summary: Option[Summary],
    location: Option[Location],
    threadName: String,
    timeStamp: Long
  )
  case InfoProvided(
    message: String,
    throwable: Option[DecodedThrowable],
    location: Option[Location],
    threadName: String,
    timeStamp: Long
  )
  case AlertProvided(
    message: String,
    throwable: Option[DecodedThrowable],
    location: Option[Location],
    threadName: String,
    timeStamp: Long
  )
  case NoteProvided(
    message: String,
    throwable: Option[DecodedThrowable],
    location: Option[Location],
    threadName: String,
    timeStamp: Long
  )
  case MarkupProvided(text: String, location: Option[Location], threadName: String, timeStamp: Long)
  case ScopeOpened(location: Option[Location], threadName: String, timeStamp: Long)
  case ScopeClosed(location: Option[Location], threadName: String, timeStamp: Long)
  case ScopePending(location: Option[Location], threadName: String, timeStamp: Long)
  case DiscoveryStarting(threadName: String, timeStamp: Long)
  case DiscoveryCompleted(duration: Option[Long], threadName: String, timeStamp: Long)
}

private[scalatest] final case class DecodedStackDepth(
  fileName: Option[String],
  filePathname: Option[String],
  lineNumber: Option[Int]
)

private[scalatest] final case class DecodedStackFrame(
  className: String,
  methodName: String,
  fileName: Option[String],
  lineNumber: Option[Int],
  isNative: Boolean,
  text: String
)

// Not a case class, and linked after construction: a throwable can be its own cause or suppressed throwable,
// so a structural equals, hashCode or toString would never return.
private[scalatest] final class DecodedThrowable(
  val className: String,
  val message: Option[String],
  val stackDepth: Option[DecodedStackDepth],
  val isPropertyCheckFailure: Boolean,
  val stackTrace: IndexedSeq[DecodedStackFrame]
) {

  private var linkedCause: Option[DecodedThrowable] = None
  private var linkedSuppressed: IndexedSeq[DecodedThrowable] = Vector.empty

  def cause: Option[DecodedThrowable] = linkedCause

  def suppressed: IndexedSeq[DecodedThrowable] = linkedSuppressed

  def link(cause: Option[DecodedThrowable], suppressed: IndexedSeq[DecodedThrowable]): Unit = {
    linkedCause = cause
    linkedSuppressed = suppressed
  }
}
