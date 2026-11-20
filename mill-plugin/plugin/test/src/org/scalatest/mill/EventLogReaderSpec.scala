package org.scalatest.mill

import java.io.FileNotFoundException
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.consolidation.Consolidation
import org.scalatest.consolidation.ConsolidationState
import org.scalatest.consolidation.RunFeed
import org.scalatest.consolidation.RunProcess
import org.scalatest.consolidation.TestRunLabel
import org.scalatest.events.MalformedTestEventLogException
import org.scalatest.matchers.should
import org.scalatest.mill.EventLogs.*
import org.scalatest.wordspec.AnyWordSpec
import scala.collection.mutable
import scala.concurrent.duration.DurationInt

final class EventLogReaderSpec extends AnyWordSpec with should.Matchers {

  private val processWatchTimeoutSeconds: Long = 30

  private final class Reading(eventLogFile: EventLogFile) {
    private val consolidationUnderTest = new ConsolidationUnderTest
    val consolidation: Consolidation = consolidationUnderTest.consolidation
    val runsAdded: AtomicInteger = new AtomicInteger(0)
    val processesEndedBeforeAnyRun: AtomicInteger = new AtomicInteger(0)
    val processExited: AtomicBoolean = new AtomicBoolean(false)
    val processesWatched: mutable.Set[RunProcess] = mutable.Set.empty[RunProcess]
    private val firstWatch: CountDownLatch = new CountDownLatch(1)

    val reader: EventLogReader = new EventLogReader(eventLogFile, addRun, () => addProcessEndedBeforeAnyRun(), watch, 1.millis)

    private def addRun(readRun: RunFeed => Unit): Unit = {
      runsAdded.incrementAndGet()
      consolidation.addRun(TestRunLabel("core.test"))(readRun)
    }

    private def addProcessEndedBeforeAnyRun(): Unit = {
      processesEndedBeforeAnyRun.incrementAndGet()
      consolidation.addProcessEndedBeforeAnyRun()
    }

    private def watch(process: RunProcess): Boolean = {
      processesWatched.synchronized(processesWatched += process)
      firstWatch.countDown()
      processExited.get
    }

    def awaitFirstWatch(): Boolean = firstWatch.await(processWatchTimeoutSeconds, TimeUnit.SECONDS)

    def outputWithoutSummaryLine: Seq[String] = consolidationUnderTest.outputWithoutSummaryLine

    def stateAfterEnd(workerProcess: WorkerProcess = WorkerProcess.Started): ConsolidationState = {
      reader.workerHasEnded(workerProcess)
      reader.awaitStreamEnd()
      consolidation.end()
    }
  }

  // Windows fails to open a file while another process holds it.
  private def openingAfterFailures(file: Path, failures: Int): EventLogFile = {
    val failed = new AtomicInteger(0)
    val onDisk = EventLogFile.at(file)
    new EventLogFile {
      def name: String = onDisk.name

      def exists: Boolean = onDisk.exists

      def open(): EventLogInput =
        if (failed.getAndIncrement() < failures) {
          throw sharingViolation(file)
        } else {
          onDisk.open()
        }
    }
  }

  "An event log reader" when {
    "its worker started a process and ends with no event log file" should {
      "add no run, but add the worker's process as one that ended before any run existed in it" in {
        withEventLogFile { file =>
          val reading = new Reading(EventLogFile.at(file))
          reading.reader.start()
          reading.stateAfterEnd() shouldBe ConsolidationState.EndedFailed
          reading.runsAdded.get shouldBe 0
          reading.processesEndedBeforeAnyRun.get shouldBe 1
        }
      }
    }

    "its worker started no process and ends with no event log file" should {
      "add neither a run nor a process, and leave the consolidation unfailed" in {
        withEventLogFile { file =>
          val reading = new Reading(EventLogFile.at(file))
          reading.reader.start()
          reading.stateAfterEnd(WorkerProcess.NotStarted) shouldBe ConsolidationState.Ended
          reading.runsAdded.get shouldBe 0
          reading.processesEndedBeforeAnyRun.get shouldBe 0
        }
      }
    }

    "the event log holds a run that completes, and its process has exited" should {
      "add the run, with its every event, and end its event stream" in {
        withEventLogFile { file =>
          appendCompletedRun(file)
          val reading = new Reading(EventLogFile.at(file))
          reading.processExited.set(true)
          reading.reader.start()
          reading.stateAfterEnd() shouldBe ConsolidationState.Ended
          reading.runsAdded.get shouldBe 1
          reading.processesEndedBeforeAnyRun.get shouldBe 0
          reading.outputWithoutSummaryLine shouldBe Seq("[core.test]")
        }
      }
    }

    "the event log file cannot be opened at first" should {
      "keep trying to open it, then read the run" in {
        withEventLogFile { file =>
          appendCompletedRun(file)
          val reading = new Reading(openingAfterFailures(file, 3))
          reading.processExited.set(true)
          reading.reader.start()
          reading.stateAfterEnd() shouldBe ConsolidationState.Ended
          reading.runsAdded.get shouldBe 1
        }
      }
    }

    "the event log file never opens" should {
      "fail with what opening it threw" in {
        withEventLogFile { file =>
          appendCompletedRun(file)
          val reading = new Reading(openingAfterFailures(file, Int.MaxValue))
          reading.processExited.set(true)
          reading.reader.start()
          reading.reader.workerHasEnded(WorkerProcess.Started)
          a[FileNotFoundException] should be thrownBy reading.reader.awaitStreamEnd()
        }
      }
    }

    "the run's process is still running" should {
      "keep reading events the run appends until the process exits" in {
        withEventLogFile { file =>
          append(file, s"$runStarted\n")
          val reading = new Reading(EventLogFile.at(file))
          reading.reader.start()
          reading.awaitFirstWatch() shouldBe true
          append(file, s"$runEndedCompleted\n")
          reading.processExited.set(true)
          reading.stateAfterEnd() shouldBe ConsolidationState.Ended
        }
      }
    }

    "the event log holds a runStarted event" should {
      "watch the process the event names" in {
        withEventLogFile { file =>
          appendCompletedRun(file)
          val reading = new Reading(EventLogFile.at(file))
          reading.processExited.set(true)
          reading.reader.start()
          reading.stateAfterEnd()
          reading.processesWatched.map(_.pid.value) shouldBe Set(4242L)
          reading.processesWatched.map(_.startTime.toString) shouldBe Set("2026-11-14T01:02:03.456Z")
        }
      }
    }

    "the run's process exits without the run's runEnded event" should {
      "end the run's event stream, so that the run does not finish" in {
        withEventLogFile { file =>
          append(file, s"$runStarted\n")
          val reading = new Reading(EventLogFile.at(file))
          reading.processExited.set(true)
          reading.reader.start()
          reading.stateAfterEnd() shouldBe ConsolidationState.EndedFailed
        }
      }
    }

    "the event log's last line is torn" should {
      "add no event for it" in {
        withEventLogFile { file =>
          append(file, s"$runStarted\n${runEndedCompleted.take(20)}")
          val reading = new Reading(EventLogFile.at(file))
          reading.processExited.set(true)
          reading.reader.start()
          reading.stateAfterEnd() shouldBe ConsolidationState.EndedFailed
        }
      }
    }

    "a line cannot be decoded" should {
      "make the reader throw, naming the line and why it cannot be decoded" in {
        withEventLogFile { file =>
          append(file, s"$runStarted\nnot an event\n$runEndedCompleted\n")
          val reading = new Reading(EventLogFile.at(file))
          reading.reader.start()
          val thrown = intercept[MalformedTestEventLogException](reading.stateAfterEnd())
          thrown.line shouldBe "not an event"
          thrown.reason should not be empty
        }
      }
      "end the run's event stream there, though the process still runs" in {
        withEventLogFile { file =>
          append(file, s"$runStarted\nnot an event\n$runEndedCompleted\n")
          val reading = new Reading(EventLogFile.at(file))
          reading.reader.start()
          intercept[MalformedTestEventLogException](reading.stateAfterEnd())
          reading.consolidation.end()
          reading.consolidation.state shouldBe ConsolidationState.EndedFailed
        }
      }
    }
  }
}
