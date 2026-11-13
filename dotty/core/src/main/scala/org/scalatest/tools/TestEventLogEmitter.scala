package org.scalatest.tools

import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import org.scalatest.events.Event
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

  /**
   * Writes an event assigned to the run. After the event that closes the run it writes how the run ended, and
   * nothing after that.
   *
   * An event that cannot be encoded or written ends the event log there, which leaves the run without its
   * `runEnded` event. So that a hand-off cannot change what the run's reporters receive, what encoding or writing
   * throws does not reach the caller, with these exceptions:
   *
   *  - an `InterruptedException` is not thrown either, but the calling thread is left interrupted;
   *  - a `VirtualMachineError` other than a `StackOverflowError`, or a `ThreadDeath`, is thrown once the event log
   *    is closed. A `StackOverflowError` is contained: a throwable or config map value that recurses without end
   *    is one more event that cannot be encoded.
   */
  def runEvent(event: Event): Unit = {
    // Encoded outside the lock: encoding calls the methods of the run's own throwables and config map values, which
    // may block, and every thread of the run takes the lock.
    val encoded = TestEventLogEmitter.caught(TestEventLogEncoder.event(event, None))
    val thrown = synchronized {
      if (isOpen) {
        val runEnded = TestEventLogEmitter.outcomeOfClosing(event).map(TestEventLogEncoder.runEnded)
        val written = encoded.flatMap(line => TestEventLogEmitter.caught((line +: runEnded.toList).foreach(write)))
        if (written.isLeft || runEnded.isDefined) {
          close()
        }
        written.left.toOption
      } else {
        encoded.left.toOption
      }
    }
    thrown.foreach {
      case _: InterruptedException => Thread.currentThread().interrupt()
      case uncontained if !TestEventLogEmitter.isContained(uncontained) => throw uncontained
      case _ => ()
    }
  }

  /** Closes the event log of a run that ends without an event that closes it, leaving it without its `runEnded` event. */
  def endWithoutRunEnded(): Unit = {
    synchronized {
      if (isOpen) {
        close()
      }
    }
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

  // Whether a hand-off keeps a throwable from its caller. One that says the JVM or the thread cannot go on is not kept.
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
