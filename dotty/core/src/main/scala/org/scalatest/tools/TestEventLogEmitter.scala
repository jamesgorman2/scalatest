package org.scalatest.tools

import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicLong
import org.scalatest.Filter
import org.scalatest.Reporter
import org.scalatest.Suite
import org.scalatest.events.Event
import org.scalatest.events.NodeId
import org.scalatest.events.RunAborted
import org.scalatest.events.RunCompleted
import org.scalatest.events.RunOutcome
import org.scalatest.events.RunStopped
import org.scalatest.events.TestEventLogEncoder

/**
 * Writes one run's test event log to a file, which already holds the run's `runStarted` event.
 *
 * Each event is appended as one unbuffered write, under this emitter's lock, so that events handed off from
 * different threads never interleave and a reader sees each event as soon as it is handed off.
 */
private[scalatest] final class TestEventLogEmitter private (eventLog: OutputStream) {

  private var isOpen = true
  private val mintedNodes = new AtomicLong(0L)

  /**
   * Writes an event assigned to the run. After the event that closes the run it writes how the run ended, and
   * nothing after that.
   *
   * An event that cannot be encoded or written ends the event log there, which leaves the run without its
   * `runEnded` event. So that a hand-off cannot change what the run's reporters receive, what encoding or writing
   * throws does not reach the caller. Two kinds of throwable are treated differently:
   *
   *  - an `InterruptedException` does not reach the caller either, but the calling thread is left interrupted;
   *  - a `VirtualMachineError` other than a `StackOverflowError`, or a `ThreadDeath`, is thrown once the event log
   *    is closed. A `StackOverflowError` is not thrown: a throwable or config map value that recurses without end
   *    is one more event that cannot be encoded.
   */
  def runEvent(event: Event): Unit = {
    // Encoded outside the lock: encoding calls the methods of the run's own throwables and config map values, which
    // may block, and every thread of the run takes the lock.
    val encoded = TestEventLogEmitter.caught(TestEventLogEncoder.event(event, None))
    append(encoded, TestEventLogEmitter.outcomeOfClosing(event).map(TestEventLogEncoder.runEnded))
  }

  /**
   * Returns the structure wrapper of a top-level suite, over the reporter the suite would report to. As it is
   * installed, the wrapper's suite gets its node table, and the event log the suite's structure: the suite, its
   * nested suites, and the scopes and tests the filter keeps.
   *
   * A structure that cannot be read ends the event log before it, as an event that cannot be encoded does, and the
   * suite then reports to the reporter itself. What reading or writing the structure throws is kept from the caller
   * as `runEvent` keeps it.
   */
  def wrapperOfTopLevelSuite(suite: Suite, filter: Filter, reporter: Reporter): Reporter = {
    // Read outside the lock: reading calls the suite's own methods.
    TestEventLogEmitter.caught(SuiteStructure.read(suite, filter)) match {
      case Right(structure) => new StructureWrapper(reporter, topLevelSuite(structure), suiteEvent)
      case Left(thrown) =>
        endWithoutRunEnded()
        TestEventLogEmitter.rethrowIfUncontained(thrown)
        reporter
    }
  }

  /**
   * Mints a node for a top-level suite and for each node of its structure, writes the structure, and returns the
   * suite's node table. No node ID is minted twice in the run.
   */
  def topLevelSuite(structure: SuiteStructure): SuiteNodeTable = {
    val (table, nodes) = SuiteNodeTable.minted(structure, () => NodeId(mintedNodes.incrementAndGet()))
    append(TestEventLogEmitter.caught(TestEventLogEncoder.structure(nodes)), None)
    table
  }

  /**
   * Takes an event a structure wrapper received, with the node table the wrapper names. It writes nothing: the
   * event log holds only the run's own events and each top-level suite's structure.
   */
  def suiteEvent(table: SuiteNodeTable, event: Event): Unit = ()

  /** Closes the event log of a run that ends without an event that closes it, leaving it without its `runEnded` event. */
  def endWithoutRunEnded(): Unit = {
    synchronized {
      if (isOpen) {
        close()
      }
    }
  }

  // Writes a line, and after it that the run ended where the line closes the run. A line that could not be encoded
  // or written closes the event log.
  private def append(encoded: Either[Throwable, String], runEnded: Option[String]): Unit = {
    val thrown = synchronized {
      if (isOpen) {
        val written = encoded.flatMap(line => TestEventLogEmitter.caught((line +: runEnded.toList).foreach(write)))
        if (written.isLeft || runEnded.isDefined) {
          close()
        }
        written.left.toOption
      } else {
        encoded.left.toOption
      }
    }
    thrown.foreach(TestEventLogEmitter.rethrowIfUncontained)
  }

  private def write(line: String): Unit = eventLog.write(line.getBytes(StandardCharsets.UTF_8))

  private def close(): Unit = {
    isOpen = false
    try eventLog.close() catch { case contained: Throwable if TestEventLogEmitter.isContained(contained) => () }
  }
}

private[scalatest] object TestEventLogEmitter {

  /** Starts an emitter where a run is given a test event log file. */
  def startedIfGiven(eventLogFile: Option[String]): Option[TestEventLogEmitter] =
    eventLogFile.map(file => started(Paths.get(file), ProcessIdentity.current))

  /** Returns the hand-off for the events a run's runner sends outside every structure wrapper. */
  def handOffTo(emitter: Option[TestEventLogEmitter]): Event => Unit = {
    emitter match {
      case Some(started) => started.runEvent(_)
      case None => TestEventLogHandOff.none
    }
  }

  /**
   * Returns what gives each top-level suite of a run its reporter: its structure wrapper where the run writes a
   * test event log, and otherwise the reporter the suite would report to.
   */
  def reporterOfTopLevelSuiteFor(emitter: Option[TestEventLogEmitter]): (Suite, Filter, Reporter) => Reporter = {
    emitter match {
      case Some(started) => started.wrapperOfTopLevelSuite(_, _, _)
      case None => TestEventLogHandOff.noStructureWrapper
    }
  }

  /** Runs a hand-off, keeping what it throws from the caller as `runEvent` keeps what encoding or writing throws. */
  def keptFromCaller(handOff: => Unit): Unit = caught(handOff).left.foreach(rethrowIfUncontained)

  /**
   * Returns an emitter whose file holds the run's `runStarted` event.
   *
   * The event is written to a file of another name and moved into place, so the event log file never exists
   * without its whole `runStarted` event.
   *
   * @throws IllegalStateException where the process has no identity to write
   */
  def started(eventLogFile: Path, processIdentity: Either[String, ProcessIdentity]): TestEventLogEmitter = {
    processIdentity match {
      case Right(identity) =>
        val runStarted = TestEventLogEncoder.runStarted(identity.pid, identity.startTime.toString)
        val unmoved = eventLogFile.resolveSibling(eventLogFile.getFileName.toString + ".tmp")
        Files.write(unmoved, runStarted.getBytes(StandardCharsets.UTF_8))
        Files.move(unmoved, eventLogFile, StandardCopyOption.ATOMIC_MOVE)
        // Not a FileChannel: interrupting a thread that is writing to one closes it for every thread.
        appendingTo(new FileOutputStream(eventLogFile.toFile, true))
      case Left(reason) =>
        throw new IllegalStateException(s"Cannot write the test event log $eventLogFile: $reason")
    }
  }

  /** Returns an emitter that appends to an event log which already holds the run's `runStarted` event. */
  def appendingTo(eventLog: OutputStream): TestEventLogEmitter = new TestEventLogEmitter(eventLog)

  private def caught[A](body: => A): Either[Throwable, A] =
    try Right(body) catch { case thrown: Throwable => Left(thrown) }

  private def rethrowIfUncontained(thrown: Throwable): Unit = {
    thrown match {
      case _: InterruptedException => Thread.currentThread().interrupt()
      case uncontained if !isContained(uncontained) => throw uncontained
      case _ => ()
    }
  }

  // Whether a hand-off keeps a throwable from its caller. One that says the JVM or the thread cannot go on reaches it.
  private def isContained(thrown: Throwable): Boolean = {
    thrown match {
      case _: StackOverflowError => true
      case _: VirtualMachineError | _: ThreadDeath => false
      case _ => true
    }
  }

  private def outcomeOfClosing(event: Event): Option[RunOutcome] = {
    event match {
      case _: RunCompleted => Some(RunOutcome.Completed)
      case _: RunStopped => Some(RunOutcome.Stopped)
      case _: RunAborted => Some(RunOutcome.Aborted)
      case _ => None
    }
  }
}
