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
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt

final class WorkerRunSpec extends AnyWordSpec with should.Matchers {

  private val label: TestRunLabel = TestRunLabel("core.test")
  private val runStarted: String = """{"item":"runStarted","pid":4242,"startTime":"2026-11-14T01:02:03.456Z"}"""
  private val runEndedCompleted: String = """{"item":"runEnded","outcome":"completed"}"""

  private final class ConsolidationUnderTest {
    val output: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty[String]
    val consolidation: Consolidation = Consolidation.start(line => output += line, coloured = false)

    def outputWithoutSummaryLine: Seq[String] = output.toSeq.dropRight(1)
  }

  private final class Worker(
      eventLogFile: Path,
      open: Path => InputStream,
      workerName: String = "worker 0 of fork group 1 of core.test",
      consolidationUnderTest: ConsolidationUnderTest = new ConsolidationUnderTest
  ) {
    val consolidation: Consolidation = consolidationUnderTest.consolidation
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
    val workerRun: WorkerRun = new WorkerRun(consolidation, workerName, reader, capture)

    def runAndEndConsolidation(startWorker: => Future[Boolean]): Unit = {
      run(startWorker)
      consolidation.end()
    }

    def run(startWorker: => Future[Boolean]): Unit = {
      workerRun.start(startWorker)
      endWorker()
    }

    def endWorker(): Unit = workerRun.end(worker => Await.ready(worker, Duration.Inf))

    def outputWithoutSummaryLine: Seq[String] = consolidationUnderTest.outputWithoutSummaryLine
  }

  private def withEventLogFile[A](test: Path => A): A = {
    val folder = Files.createTempDirectory("worker-run")
    try {
      test(folder.resolve(JvmTestModuleRun.eventLogFileName))
    } finally {
      Files.walk(folder).sorted(java.util.Comparator.reverseOrder()).forEach(path => Files.delete(path))
    }
  }

  private def writeCompletedRun(file: Path): Unit =
    Files.write(file, s"$runStarted\n$runEndedCompleted\n".getBytes(StandardCharsets.UTF_8))

  private val opening: Path => InputStream = path => new FileInputStream(path.toFile)

  private val sharingViolation: Path => InputStream =
    path => throw new FileNotFoundException(s"$path (The process cannot access the file because it is being used by another process)")

  private val startedAProcess: Future[Boolean] = Future.successful(true)

  "Running a worker" when {
    "its event log file never becomes readable" should {
      "attach what reading threw to the root, end the run's event stream and fail the consolidation" in {
        withEventLogFile { file =>
          writeCompletedRun(file)
          val worker = new Worker(file, sharingViolation)
          worker.runAndEndConsolidation(startedAProcess)
          worker.outputWithoutSummaryLine shouldBe Seq(
            "Consolidated tests:",
            "  [core.test]",
            "  *** CONSOLIDATED TESTS ABORTED ***",
            s"    Reading the event log of worker 0 of fork group 1 of core.test failed: java.io.FileNotFoundException: $file (The process cannot access the file because it is being used by another process)"
          )
          worker.consolidation.state shouldBe ConsolidationState.EndedFailed
        }
      }
    }

    "starting the worker throws after the worker wrote an unterminated line" should {
      "end the capture streams, attach what starting threw to the root and fail the consolidation" in {
        withEventLogFile { file =>
          writeCompletedRun(file)
          val worker = new Worker(file, opening)
          worker.runAndEndConsolidation {
            val bytes = "partial".getBytes(StandardCharsets.UTF_8)
            worker.capture.stdout(bytes, bytes.length)
            throw new IllegalStateException("testargs already exists")
          }
          worker.outputWithoutSummaryLine shouldBe Seq(
            "Consolidated tests:",
            "  [core.test]",
            "    Run 1:",
            "      [stdout] partial",
            "  *** CONSOLIDATED TESTS ABORTED ***",
            "    Starting worker 0 of fork group 1 of core.test failed: java.lang.IllegalStateException: testargs already exists"
          )
          worker.consolidation.state shouldBe ConsolidationState.EndedFailed
        }
      }
    }

    "starting the worker throws before its event log exists" should {
      "attach what starting threw to the root and fail the consolidation" in {
        withEventLogFile { file =>
          val worker = new Worker(file, opening)
          worker.runAndEndConsolidation(throw new IllegalStateException("testargs already exists"))
          worker.outputWithoutSummaryLine shouldBe Seq(
            "Consolidated tests:",
            "  *** CONSOLIDATED TESTS ABORTED ***",
            "    Starting worker 0 of fork group 1 of core.test failed: java.lang.IllegalStateException: testargs already exists"
          )
          worker.consolidation.state shouldBe ConsolidationState.EndedFailed
        }
      }
    }

    "the worker started on a future fails after it started" should {
      "attach what it failed with to the root, naming the worker, and fail the consolidation" in {
        withEventLogFile { file =>
          val worker = new Worker(file, opening)
          worker.runAndEndConsolidation(Future.failed(new IllegalStateException("claim folder missing")))
          worker.outputWithoutSummaryLine shouldBe Seq(
            "Consolidated tests:",
            "  *** CONSOLIDATED TESTS ABORTED ***",
            "    Starting worker 0 of fork group 1 of core.test failed: java.lang.IllegalStateException: claim folder missing"
          )
          worker.consolidation.state shouldBe ConsolidationState.EndedFailed
        }
      }
    }

    "the worker started no process, as where it found its fork group's queue empty" should {
      "add neither a run nor a process that ended before any run, and leave the consolidation unfailed" in {
        withEventLogFile { file =>
          val worker = new Worker(file, opening)
          worker.runAndEndConsolidation(Future.successful(false))
          worker.processesEndedBeforeAnyRun.get shouldBe 0
          worker.consolidation.state shouldBe ConsolidationState.Ended
        }
      }
    }

    "the worker started a process in which no run existed" should {
      "add that process as one that ended before any run" in {
        withEventLogFile { file =>
          val worker = new Worker(file, opening)
          worker.runAndEndConsolidation(startedAProcess)
          worker.processesEndedBeforeAnyRun.get shouldBe 1
          worker.consolidation.state shouldBe ConsolidationState.EndedFailed
        }
      }
    }

    "the worker runs on a future" should {
      "read the event log the worker writes after it started, and add its run" in {
        withEventLogFile { file =>
          val worker = new Worker(file, opening)
          val workerEnd = Promise[Boolean]()
          worker.workerRun.start(workerEnd.future)
          writeCompletedRun(file)
          workerEnd.success(true)
          worker.endWorker()
          worker.consolidation.end()
          worker.outputWithoutSummaryLine shouldBe Seq("[core.test]")
          worker.processesEndedBeforeAnyRun.get shouldBe 0
          worker.consolidation.state shouldBe ConsolidationState.Ended
        }
      }
    }

    "starting the first of two workers throws before its event log exists" should {
      "attach what starting threw to the root, naming the first worker, and still give the second worker's run to the consolidation" in {
        withEventLogFile { firstFile =>
          withEventLogFile { secondFile =>
            writeCompletedRun(secondFile)
            val consolidationUnderTest = new ConsolidationUnderTest
            val first = new Worker(firstFile, opening, "worker 0 of fork group 0 of core.test", consolidationUnderTest)
            val second = new Worker(secondFile, opening, "worker 0 of fork group 1 of core.test", consolidationUnderTest)
            first.run(throw new IllegalStateException("testargs already exists"))
            second.run {
              val bytes = "second\n".getBytes(StandardCharsets.UTF_8)
              second.capture.stdout(bytes, bytes.length)
              startedAProcess
            }
            consolidationUnderTest.consolidation.end()
            consolidationUnderTest.outputWithoutSummaryLine shouldBe Seq(
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
          val worker = new Worker(file, opening)
          a[StackOverflowError] should be thrownBy worker.workerRun.start(throw new StackOverflowError("worker"))
          worker.processesEndedBeforeAnyRun.get shouldBe 1
        }
      }
    }
  }
}
