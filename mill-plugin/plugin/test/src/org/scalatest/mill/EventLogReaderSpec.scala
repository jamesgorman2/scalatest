package org.scalatest.mill

import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.consolidation.Consolidation
import org.scalatest.consolidation.ConsolidationState
import org.scalatest.consolidation.RunFeed
import org.scalatest.consolidation.RunProcess
import org.scalatest.consolidation.TestRunLabel
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import scala.collection.mutable
import scala.concurrent.duration.DurationInt

final class EventLogReaderSpec extends AnyWordSpec with should.Matchers {

  private val runStarted: String = """{"item":"runStarted","pid":4242,"startTime":"2026-11-14T01:02:03.456Z"}"""
  private val runEndedCompleted: String = """{"item":"runEnded","outcome":"completed"}"""

  /** A consolidation the reader adds its run to, and the reader's view of the run's process. */
  private final class Reading(eventLogFile: Path, open: Path => InputStream = path => new FileInputStream(path.toFile)) {
    val output: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty[String]
    val consolidation: Consolidation = Consolidation.start(line => output += line, coloured = false)
    val runsAdded: AtomicInteger = new AtomicInteger(0)
    val processesEndedBeforeAnyRun: AtomicInteger = new AtomicInteger(0)
    val processExited: AtomicBoolean = new AtomicBoolean(false)
    val processesWatched: mutable.Set[RunProcess] = mutable.Set.empty[RunProcess]

    val reader: EventLogReader = new EventLogReader(eventLogFile, () => addRun(), () => addProcessEndedBeforeAnyRun(), watch, 1.millis, open)

    private def addRun(): RunFeed = {
      runsAdded.incrementAndGet()
      consolidation.addRun(TestRunLabel("core.test"))
    }

    private def addProcessEndedBeforeAnyRun(): Unit = {
      processesEndedBeforeAnyRun.incrementAndGet()
      consolidation.addProcessEndedBeforeAnyRun()
    }

    private def watch(process: RunProcess): Boolean = {
      processesWatched.synchronized(processesWatched += process)
      processExited.get
    }

    /** The consolidation's state once the reader's run has ended and the consolidation has. */
    def stateAfterEnd(): ConsolidationState = {
      reader.awaitStreamEnd()
      consolidation.end()
      consolidation.state
    }
  }

  private def withEventLogFile[A](test: Path => A): A = {
    val folder = Files.createTempDirectory("event-log-reader")
    try {
      test(folder.resolve("scalatest-event-log.jsonl"))
    } finally {
      Files.walk(folder).sorted(java.util.Comparator.reverseOrder()).forEach(path => Files.delete(path))
    }
  }

  /** Opens the file once `failures` opens have failed as a file another process holds fails to open on Windows. */
  private def openingAfterFailures(failures: Int): Path => InputStream = {
    val failed = new AtomicInteger(0)
    path =>
      if (failed.getAndIncrement() < failures) {
        throw new FileNotFoundException(s"$path (The process cannot access the file because it is being used by another process)")
      } else {
        new FileInputStream(path.toFile)
      }
  }

  private def write(file: Path, text: String): Unit =
    Files.write(file, text.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND)

  "An event log reader" when {
    "its worker ends with no event log file" should {
      "add no run, but add the worker as a process that ended before any run existed in it" in {
        withEventLogFile { file =>
          val reading = new Reading(file)
          reading.reader.start()
          reading.stateAfterEnd() shouldBe ConsolidationState.EndedFailed
          reading.runsAdded.get shouldBe 0
          reading.processesEndedBeforeAnyRun.get shouldBe 1
        }
      }
    }

    "the event log holds a run that completes, and its process has exited" should {
      "add the run, with its every event, and end its event stream" in {
        withEventLogFile { file =>
          write(file, s"$runStarted\n$runEndedCompleted\n")
          val reading = new Reading(file)
          reading.processExited.set(true)
          reading.reader.start()
          reading.stateAfterEnd() shouldBe ConsolidationState.Ended
          reading.runsAdded.get shouldBe 1
          reading.processesEndedBeforeAnyRun.get shouldBe 0
          reading.output.toSeq shouldBe Seq("[core.test]")
        }
      }
    }

    "the event log file cannot be opened at first" should {
      "keep trying to open it, then read the run" in {
        withEventLogFile { file =>
          write(file, s"$runStarted\n$runEndedCompleted\n")
          val reading = new Reading(file, openingAfterFailures(3))
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
          write(file, s"$runStarted\n$runEndedCompleted\n")
          val reading = new Reading(file, openingAfterFailures(Int.MaxValue))
          reading.processExited.set(true)
          reading.reader.start()
          a[FileNotFoundException] should be thrownBy reading.reader.awaitStreamEnd()
        }
      }
    }

    "the run's process is still running" should {
      "keep reading events the run appends until the process exits" in {
        withEventLogFile { file =>
          write(file, s"$runStarted\n")
          val reading = new Reading(file)
          reading.reader.start()
          Thread.sleep(50)
          write(file, s"$runEndedCompleted\n")
          reading.processExited.set(true)
          reading.stateAfterEnd() shouldBe ConsolidationState.Ended
        }
      }
    }

    "the event log holds a runStarted event" should {
      "watch the process the event names" in {
        withEventLogFile { file =>
          write(file, s"$runStarted\n$runEndedCompleted\n")
          val reading = new Reading(file)
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
          write(file, s"$runStarted\n")
          val reading = new Reading(file)
          reading.processExited.set(true)
          reading.reader.start()
          reading.stateAfterEnd() shouldBe ConsolidationState.EndedFailed
        }
      }
    }

    "the event log's last line is torn" should {
      "add no event for it" in {
        withEventLogFile { file =>
          write(file, s"$runStarted\n${runEndedCompleted.take(20)}")
          val reading = new Reading(file)
          reading.processExited.set(true)
          reading.reader.start()
          reading.stateAfterEnd() shouldBe ConsolidationState.EndedFailed
        }
      }
    }

    "a line cannot be decoded" should {
      "end the run's event stream there, though the process still runs" in {
        withEventLogFile { file =>
          write(file, s"$runStarted\nnot an event\n$runEndedCompleted\n")
          val reading = new Reading(file)
          reading.reader.start()
          reading.stateAfterEnd() shouldBe ConsolidationState.EndedFailed
        }
      }
    }
  }
}
