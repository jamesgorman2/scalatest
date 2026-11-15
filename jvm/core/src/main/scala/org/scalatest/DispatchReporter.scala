/*
 * Copyright 2001-2013 Artima, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.scalatest

import java.io.PrintStream
import org.scalatest.events._
import org.scalactic.Requirements._
import Reporter.propagateDispose
import time.Now._
import java.util.concurrent.atomic.AtomicReference
import tools.StringReporter.makeDurationString
import tools.SuiteSortingReporter
import tools.TestEventLogHandOff

/**
 * A <code>Reporter</code> that dispatches test results to other <code>Reporter</code>s.
 * Attempts to dispatch each method invocation to each contained <code>Reporter</code>,
 * even if some <code>Reporter</code> methods throw <code>Exception</code>s. Catches
 * <code>Exception</code>s thrown by <code>Reporter</code> methods and prints error
 * messages to the standard error stream.
 *
 * The primary constructor creates a new <code>DispatchReporter</code> with specified <code>Reporter</code>s list.
 * Each object in the <code>reporters</code> list must implement <code>Reporter</code>.
 *
 * @param reporters the initial <code>Reporter</code>s list for this
 * <code>DispatchReporter</code>
 * @throws NullArgumentException if <code>reporters</code> is <code>null</code>.
 * @author Bill Venners
 */
private[scalatest] class DispatchReporter(
  val reporters: List[Reporter],
  val out: PrintStream = Console.err,
  detectSlowpokes: Boolean = false,
  slowpokeDetectionDelay: Long = 60000,
  slowpokeDetectionPeriod: Long = 60000
) extends CatchReporter { thisDispatchReporter =>

  requireNonNull(reporters)

  private val slowpokeReporter: AtomicReference[Option[SuiteSortingReporter]] = new AtomicReference[Option[SuiteSortingReporter]](None)

  def registerSlowpokeReporter(reporter: SuiteSortingReporter): Unit =
    slowpokeReporter.getAndSet(Some(reporter))

  private val testEventLogHandOff: AtomicReference[Event => Unit] = new AtomicReference[Event => Unit](TestEventLogHandOff.none)

  /** Takes the hand-off of the events this reporter raises or completes: its slowpoke alerts and the closing run events. */
  def registerTestEventLogHandOff(handOffToTestEventLog: Event => Unit): Unit =
    testEventLogHandOff.set(handOffToTestEventLog)

  /**
   * Reports a closing run event of this reporter's own run and, once every reporter has it, hands off the event they
   * received, which carries the run's summary. It returns once the event is handed off and throws what the hand-off
   * throws, so that a hand-off throws to its send site, not on the dispatcher's thread. Closing run events relayed from
   * another process are reported with `apply`, which hands nothing off.
   */
  def reportAndHandOff(closingRunEvent: Event): Unit = {
    val handOff = testEventLogHandOff.get
    if ((handOff eq TestEventLogHandOff.none) || isDisposed)
      apply(closingRunEvent)
    else {
      val closing = new ClosingRunEventToHandOff(closingRunEvent, handOff)
      queue.put(closing)
      closing.awaitHandOff()
    }
  }

  private final class ClosingRunEventToHandOff(val event: Event, handOffToTestEventLog: Event => Unit) {

    private val handedOff = new java.util.concurrent.CountDownLatch(1)
    private val thrown = new AtomicReference[Option[Throwable]](None)

    def handOff(reported: Event): Unit = {
      try handOffToTestEventLog(reported)
      catch {
        case uncontained: Throwable => thrown.set(Some(uncontained))
      }
      finally {
        // A hand-off interrupted while encoding leaves its thread interrupted, which would end the dispatcher's next
        // take from its queue.
        Thread.interrupted()
      }
    }

    def complete(): Unit = handedOff.countDown()

    def awaitHandOff(): Unit = {
      var interrupted = false
      var waiting = true
      while (waiting) {
        try {
          handedOff.await()
          waiting = false
        }
        catch {
          case _: InterruptedException => interrupted = true
        }
      }
      if (interrupted)
        Thread.currentThread().interrupt()
      thrown.get.foreach(uncontained => throw uncontained)
    }
  }

  private case object Dispose

  private final val latch = new CountDownLatch(1)

  // We keep track of the highest ordinal seen, allowing some to be skipped. 
  private final val highestOrdinalSeenSoFar: AtomicReference[Ordinal] = new AtomicReference[Ordinal](new Ordinal(0))

  // Can be either Event or Dispose.type. Be nice to capture that in the type param.
  private final val queue = new LinkedBlockingQueue[AnyRef]

  private final val slowpokeItems: Option[(SlowpokeDetector, Timer)] =
    if (detectSlowpokes) {
      val slowpokeDetector = new SlowpokeDetector(slowpokeDetectionDelay, out)
      val timer =  new Timer
      val task = new TimerTask {
        override def run(): Unit = {
          val slowpokes = slowpokeDetector.detectSlowpokes(now())
          if (!slowpokes.isEmpty) {
            val msgs =
              for (slowpoke <- slowpokes)
              yield Resources.slowpokeDetected(makeDurationString(slowpoke.duration.millisPart), slowpoke.suiteName, slowpoke.testName)
            val fullMessage = msgs.mkString("\n")
            val dispatch = thisDispatchReporter
            val alertEvent =
              new AlertProvided(
                ordinal = highestOrdinalSeenSoFar.get,
                message = fullMessage,
                nameInfo = None, // Don't include name info. suiteName and testName for all slowpokes are included in fullMessage already.
                throwable = None,
                formatter = Some(IndentedText(Resources.alertFormattedText(fullMessage), fullMessage, 0))
              )
            testEventLogHandOff.get.apply(alertEvent)
            thisDispatchReporter.apply(alertEvent)
            slowpokeReporter.get match {
              case Some(slowpokeRep) => slowpokeRep.slowpokeEvent(alertEvent)
              case None =>
            }
          }
        }
      }
      timer.schedule(task, slowpokeDetectionPeriod, slowpokeDetectionPeriod)

      Some((slowpokeDetector, timer))
    }
    else None

  private def fireTestFinishedIfNecessary(suiteName: String, suiteId: String, testName: String): Unit = {
    slowpokeItems match {
      case Some((slowpokeDetector, _)) => slowpokeDetector.testFinished(suiteName, suiteId, testName)
      case None =>
    }
  }

  class Propagator extends Runnable {

    def run(): Unit = {

      var alive = true // local variable. Only used by the Propagator's thread, so no need for synchronization

      class Counter {
        var testsSucceededCount = 0
        var testsFailedCount = 0
        var testsIgnoredCount = 0
        var testsCanceledCount = 0
        var testsPendingCount = 0
        var suitesCompletedCount = 0
        var suitesAbortedCount = 0
        var scopesPendingCount = 0
      }
  
      val counterMap = scala.collection.mutable.Map[Int, Counter]()
  
      def incrementCount(event: Event, f: (Counter) => Unit): Unit = {
        val runStamp = event.ordinal.runStamp
        if (counterMap.contains(runStamp)) {
          val counter = counterMap(runStamp)
          f(counter)
        }
        else {
          val counter = new Counter
          f(counter)
          counterMap(runStamp) = counter
        }
      }
  
      // If None, that means don't update the summary so forward the old event. If Some,
      // create a new event with everything the same except the old summary replaced by the new one
      def updatedSummary(oldSummary: Option[Summary], ordinal: Ordinal): Option[Summary] = {
        oldSummary match {
          case None if (counterMap.contains(ordinal.runStamp)) => {
              // Update the RunAborted so that it is the same except it has a new Some(Summary)
              val counter = counterMap(ordinal.runStamp)
              Some(
                Summary(
                  counter.testsSucceededCount,
                  counter.testsFailedCount,
                  counter.testsIgnoredCount,
                  counter.testsPendingCount,
                  counter.testsCanceledCount,
                  counter.suitesCompletedCount,
                  counter.suitesAbortedCount, 
                  counter.scopesPendingCount
                )
              )
            }
           case _ => None // Also pass the old None summary through if it isn't in the counterMap
        }
      }
  
      // Reports an event to the reporters and returns the event they received.
      def propagate(event: Event): Event = {
        val highestSoFar = highestOrdinalSeenSoFar.get
        if (event.ordinal > highestSoFar)
          highestOrdinalSeenSoFar.compareAndSet(highestSoFar, event.ordinal) // Ignore conflicts. Just let first one win and move on.
          // The reason is that this is used to send AlertProvided events for slowpoke notifications, and these need not have the
          // exactly the latest ordinal. So long as it is in the ballpark, that's is good enough.
          
        var reported = event
        try {
          // The event will only actually be updated if it it is a RunCompleted/Aborted/Stopped event with None
          // as its summary and its runstamp has a counter entry. In that case, it will be given a Summary taken
          // from the counter. (And the counter will be removed from the counterMap.) These are counted here, because
          // they need to be counted on this side of any FilterReporters that may be in place. (In early versions of
          // ScalaTest, these were wrongly being counted by the reporters themselves, so if a FilterReporter filtered
          // out TestSucceeded events, then they just weren't being counted.
          val updatedEvent =
            event match {
  
              case _: RunStarting => counterMap(event.ordinal.runStamp) = new Counter; event

              case ts: TestSucceeded =>
                incrementCount(event, _.testsSucceededCount += 1)
                fireTestFinishedIfNecessary(ts.suiteName, ts.suiteId, ts.testName)
                event
              case tf: TestFailed =>
                incrementCount(event, _.testsFailedCount += 1)
                fireTestFinishedIfNecessary(tf.suiteName, tf.suiteId, tf.testName)
                event
              case _: TestIgnored => incrementCount(event, _.testsIgnoredCount += 1); event
              case tc: TestCanceled =>
                incrementCount(event, _.testsCanceledCount += 1)
                fireTestFinishedIfNecessary(tc.suiteName, tc.suiteId, tc.testName)
                event
              case tp: TestPending =>
                incrementCount(event, _.testsPendingCount += 1)
                fireTestFinishedIfNecessary(tp.suiteName, tp.suiteId, tp.testName)
                event
              case _: SuiteCompleted => incrementCount(event, _.suitesCompletedCount += 1); event
              case _: SuiteAborted => incrementCount(event, _.suitesAbortedCount += 1); event
              case _: ScopePending => incrementCount(event, _.scopesPendingCount += 1); event

              case oldRunCompleted @ RunCompleted(ordinal, duration, summary, formatter, location, payload, threadName, timeStamp) =>
                updatedSummary(summary, ordinal) match {
                  case None => oldRunCompleted
                  case newSummary @ Some(_) =>
                    counterMap.remove(ordinal.runStamp)
                    // Update the RunCompleted so that it is the same except it has a new Some(Summary)
                    RunCompleted(ordinal, duration, newSummary, formatter, location, payload, threadName, timeStamp)
                }
    
              case oldRunStopped @ RunStopped(ordinal, duration, summary, formatter, location, payload, threadName, timeStamp) =>
                updatedSummary(summary, ordinal) match {
                  case None => oldRunStopped
                  case newSummary @ Some(_) =>
                    counterMap.remove(ordinal.runStamp)
                    // Update the RunStopped so that it is the same except it has a new Some(Summary)
                    RunStopped(ordinal, duration, newSummary, formatter, location, payload, threadName, timeStamp)
                }
              
              case oldRunAborted @ RunAborted(ordinal, message, throwable, duration, summary, formatter, location, payload, threadName, timeStamp) => 
                updatedSummary(summary, ordinal) match {
                  case None => oldRunAborted
                  case newSummary @ Some(_) =>
                    counterMap.remove(ordinal.runStamp)
                    // Update the RunAborted so that it is the same except it has a new Some(Summary)
                    RunAborted(ordinal, message, throwable, duration, newSummary, formatter, location, payload, threadName, timeStamp)
                }
              
              case ts: TestStarting =>
                slowpokeItems match {
                  case Some((slowpokeDetector, _)) =>
                    slowpokeDetector.testStarting(
                      suiteName = ts.suiteName, 
                      suiteId = ts.suiteId, 
                      testName = ts.testName, 
                      now()
                    )
                  case None =>
                }
                event
              case _ => event
            }
          reported = updatedEvent
          for (report <- reporters)
            report(updatedEvent)
        }
        catch {
          case e: Exception => 
            val stringToPrint = Resources.reporterThrew(event)
            out.println(stringToPrint)
            e.printStackTrace(out)
        }
        reported
      }

      while (alive) {
        queue.take() match {
          case closing: ClosingRunEventToHandOff =>
            try closing.handOff(propagate(closing.event))
            finally closing.complete()
          case event: Event =>
            propagate(event)
          case Dispose =>
            try {
              for (reporter <- reporters)
                propagateDispose(reporter)
            }
            catch {
              case e: Exception =>
                val stringToPrint = Resources.reporterDisposeThrew
                out.println(stringToPrint)
                e.printStackTrace(out)
            }
            finally {
              alive = false
              latch.countDown()
            }
        }
      }
    }
  }

  private val propagator = new Propagator
  (new Thread(propagator, "ScalaTest-dispatcher")).start()

  // Invokes dispose on each Reporter in this DispatchReporter's reporters list.
  // This method puts an event in the queue that is being used to serialize
  // events, and at some time later the propagator's thread will attempt to invoke
  // dispose on each contained Reporter, even if some Reporter's dispose methods throw
  // Exceptions. This method catches any Exception thrown by
  // a dispose method and handles it by printing an error message to the
  // standard error stream. Once finished with that, the propagator's thread will return.
  //
  // This method will not return until the propagator's thread has exited.
  //
  def dispatchDisposeAndWaitUntilDone(): Unit = {
    queue.put(Dispose)
    latch.await()
    slowpokeItems match {
      case Some((_, timer)) => timer.cancel()
      case None =>
    }
  }

  override def apply(event: Event): Unit = {
    queue.put(event)
  }

  def doApply(event: Event): Unit = {}

  def doDispose(): Unit = {
    dispatchDisposeAndWaitUntilDone()
  }

  def isDisposed = latch.getCount == 0
}
