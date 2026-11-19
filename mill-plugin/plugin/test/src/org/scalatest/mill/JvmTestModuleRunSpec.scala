package org.scalatest.mill

import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.consolidation.Consolidation
import org.scalatest.consolidation.ConsolidationState
import org.scalatest.consolidation.TestRunLabel
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import scala.collection.mutable
import scala.concurrent.duration.DurationInt

final class JvmTestModuleRunSpec extends AnyWordSpec with should.Matchers {

  private val label: TestRunLabel = TestRunLabel("core.test")
  private val runStarted: String = """{"item":"runStarted","pid":4242,"startTime":"2026-11-14T01:02:03.456Z"}"""
  private val runEndedCompleted: String = """{"item":"runEnded","outcome":"completed"}"""

  private final class ConsolidationUnderTest {
    val output: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty[String]
    val consolidation: Consolidation = Consolidation.start(line => output += line, coloured = false)

    def outputWithoutSummaryLine: Seq[String] = output.toSeq.dropRight(1)
  }

  private final class WorkerRun(
      eventLogFile: Path,
      open: Path => InputStream,
      workerName: String = "worker 0 of fork group 1 of core.test",
      consolidated: ConsolidationUnderTest = new ConsolidationUnderTest
  ) {
    val consolidation: Consolidation = consolidated.consolidation
    val capture: WorkerOutputCapture = new WorkerOutputCapture(StandardCharsets.UTF_8)
    val processesEndedBeforeAnyRun: AtomicInteger = new AtomicInteger(0)
    private val reader = new EventLogReader(
      eventLogFile,
      () => {
        val feed = consolidation.addRun(label)
        capture.addTo(feed.add)
        feed
      },
      () => {
        processesEndedBeforeAnyRun.incrementAndGet()
        consolidation.addProcessEndedBeforeAnyRun()
      },
      hasExited = _ => true,
      pollInterval = 1.millis,
      open = open
    )

    def run(startWorker: => Unit): Unit = {
      runWorker(startWorker)
      consolidation.end()
    }

    def runWorker(startWorker: => Unit): Unit =
      JvmTestModuleRun.runWorker(consolidation, workerName, reader, capture)(startWorker)

    def outputWithoutSummaryLine: Seq[String] = consolidated.outputWithoutSummaryLine
  }

  private def withEventLogFile[A](test: Path => A): A = {
    val folder = Files.createTempDirectory("jvm-test-module-run")
    try {
      test(folder.resolve(JvmTestModuleRun.eventLogFileName))
    } finally {
      Files.walk(folder).sorted(java.util.Comparator.reverseOrder()).forEach(path => Files.delete(path))
    }
  }

  private def writeCompletedRun(file: Path): Unit =
    Files.write(file, s"$runStarted\n$runEndedCompleted\n".getBytes(StandardCharsets.UTF_8))

  private val sharingViolation: Path => InputStream =
    path => throw new FileNotFoundException(s"$path (The process cannot access the file because it is being used by another process)")

  "Running a worker" when {
    "its event log file never becomes readable" should {
      "attach what reading threw to the root, end the run's event stream and fail the consolidation" in {
        withEventLogFile { file =>
          writeCompletedRun(file)
          val workerRun = new WorkerRun(file, sharingViolation)
          workerRun.run(())
          workerRun.outputWithoutSummaryLine shouldBe Seq(
            "Consolidated tests:",
            "  [core.test]",
            "  *** CONSOLIDATED TESTS ABORTED ***",
            s"    Reading the event log of worker 0 of fork group 1 of core.test failed: java.io.FileNotFoundException: $file (The process cannot access the file because it is being used by another process)"
          )
          workerRun.consolidation.state shouldBe ConsolidationState.EndedFailed
        }
      }
    }

    "starting the worker throws after the worker wrote an unterminated line" should {
      "end the capture streams, attach what starting threw to the root and fail the consolidation" in {
        withEventLogFile { file =>
          writeCompletedRun(file)
          val workerRun = new WorkerRun(file, path => new FileInputStream(path.toFile))
          workerRun.run {
            val bytes = "partial".getBytes(StandardCharsets.UTF_8)
            workerRun.capture.stdout(bytes, bytes.length)
            throw new IllegalStateException("testargs already exists")
          }
          workerRun.outputWithoutSummaryLine shouldBe Seq(
            "Consolidated tests:",
            "  [core.test]",
            "    Run 1:",
            "      [stdout] partial",
            "  *** CONSOLIDATED TESTS ABORTED ***",
            "    Starting worker 0 of fork group 1 of core.test failed: java.lang.IllegalStateException: testargs already exists"
          )
          workerRun.consolidation.state shouldBe ConsolidationState.EndedFailed
        }
      }
    }

    "starting the worker throws before its event log exists" should {
      "attach what starting threw to the root and fail the consolidation" in {
        withEventLogFile { file =>
          val workerRun = new WorkerRun(file, path => new FileInputStream(path.toFile))
          workerRun.run(throw new IllegalStateException("testargs already exists"))
          workerRun.outputWithoutSummaryLine shouldBe Seq(
            "Consolidated tests:",
            "  *** CONSOLIDATED TESTS ABORTED ***",
            "    Starting worker 0 of fork group 1 of core.test failed: java.lang.IllegalStateException: testargs already exists"
          )
          workerRun.consolidation.state shouldBe ConsolidationState.EndedFailed
        }
      }
    }

    "starting the first of two workers throws before its event log exists" should {
      "attach what starting threw to the root, naming the first worker, and still give the second worker's run to the consolidation" in {
        withEventLogFile { firstFile =>
          withEventLogFile { secondFile =>
            writeCompletedRun(secondFile)
            val consolidated = new ConsolidationUnderTest
            val opening: Path => InputStream = path => new FileInputStream(path.toFile)
            val first = new WorkerRun(firstFile, opening, "worker 0 of fork group 0 of core.test", consolidated)
            val second = new WorkerRun(secondFile, opening, "worker 0 of fork group 1 of core.test", consolidated)
            first.runWorker(throw new IllegalStateException("testargs already exists"))
            second.runWorker {
              val bytes = "second\n".getBytes(StandardCharsets.UTF_8)
              second.capture.stdout(bytes, bytes.length)
            }
            consolidated.consolidation.end()
            consolidated.outputWithoutSummaryLine shouldBe Seq(
              "Consolidated tests:",
              "  [core.test]",
              "    Run 1:",
              "      [stdout] second",
              "  *** CONSOLIDATED TESTS ABORTED ***",
              "    Starting worker 0 of fork group 0 of core.test failed: java.lang.IllegalStateException: testargs already exists"
            )
          }
        }
      }
    }

    "starting the worker throws a fatal error before its event log exists" should {
      "rethrow it once the reader has added the worker as a process that ended before any run" in {
        withEventLogFile { file =>
          val workerRun = new WorkerRun(file, path => new FileInputStream(path.toFile))
          a[StackOverflowError] should be thrownBy workerRun.runWorker(throw new StackOverflowError("worker"))
          workerRun.processesEndedBeforeAnyRun.get shouldBe 1
        }
      }
    }
  }
}
