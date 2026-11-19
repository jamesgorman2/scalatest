package org.scalatest.mill

import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.Signaler
import org.scalatest.concurrent.ThreadSignaler
import org.scalatest.concurrent.TimeLimits
import org.scalatest.consolidation.Consolidation
import org.scalatest.consolidation.ConsolidationState
import org.scalatest.consolidation.TestRunLabel
import org.scalatest.matchers.should
import org.scalatest.time.Seconds
import org.scalatest.time.Span
import org.scalatest.wordspec.AnyWordSpec
import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt

final class WorkerRunSpec extends AnyWordSpec with should.Matchers with Eventually with TimeLimits {

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(5, Seconds))

  private val label: TestRunLabel = TestRunLabel("core.test")
  private val runStarted: String = """{"item":"runStarted","pid":4242,"startTime":"2026-11-14T01:02:03.456Z"}"""
  private val runEndedCompleted: String = """{"item":"runEnded","outcome":"completed"}"""

  private final class ConsolidationUnderTest {
    val output: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty[String]
    val consolidation: Consolidation = Consolidation.start(line => output += line, coloured = false)

    def outputWithoutSummaryLine: Seq[String] = output.toSeq.dropRight(1)
  }

  private final class WorkerRunFixture(
      eventLogFile: Path,
      open: Path => InputStream,
      workerName: String = "worker 0 of fork group 1 of core.test",
      consolidationUnderTest: ConsolidationUnderTest = new ConsolidationUnderTest
  ) {
    val consolidation: Consolidation = consolidationUnderTest.consolidation
    val capture: WorkerOutputCapture = new WorkerOutputCapture(StandardCharsets.UTF_8)
    val runsAdded: AtomicInteger = new AtomicInteger(0)
    val processesEndedBeforeAnyRun: AtomicInteger = new AtomicInteger(0)
    @volatile var processExited: Boolean = true
    private val reader = new EventLogReader(
      eventLogFile,
      () => {
        runsAdded.incrementAndGet()
        val feed = consolidation.addRun(label)
        capture.addTo(feed.add)
        feed
      },
      () => {
        processesEndedBeforeAnyRun.incrementAndGet()
        consolidation.addProcessEndedBeforeAnyRun()
      },
      hasExited = _ => processExited,
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

    def endWorker(): Unit = workerRun.end(workerEnd => Await.ready(workerEnd, Duration.Inf))

    def awaitReaderEnd(): Unit = reader.awaitStreamEnd(startedAProcess = true)

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

  private def writeStdout(capture: WorkerOutputCapture, text: String): Unit = {
    val bytes = text.getBytes(StandardCharsets.UTF_8)
    capture.stdout(bytes, bytes.length)
  }

  "Running a worker" when {
    "its event log file never becomes readable" should {
      "attach what reading threw to the root, end the run's event stream and fail the consolidation" in {
        withEventLogFile { file =>
          writeCompletedRun(file)
          val fixture = new WorkerRunFixture(file, sharingViolation)
          fixture.runAndEndConsolidation(startedAProcess)
          fixture.outputWithoutSummaryLine shouldBe Seq(
            "Consolidated tests:",
            "  [core.test]",
            "  *** CONSOLIDATED TESTS ABORTED ***",
            s"    Reading the event log of worker 0 of fork group 1 of core.test failed: java.io.FileNotFoundException: $file (The process cannot access the file because it is being used by another process)"
          )
          fixture.consolidation.state shouldBe ConsolidationState.EndedFailed
        }
      }
    }

    "starting the worker throws after the worker wrote an unterminated line" should {
      "leave the capture streams unended, so the line the worker's output pump goes on to finish is captured whole" in {
        withEventLogFile { file =>
          writeCompletedRun(file)
          val fixture = new WorkerRunFixture(file, opening)
          fixture.run {
            writeStdout(fixture.capture, "partial")
            throw new IllegalStateException("claim log unreadable")
          }
          writeStdout(fixture.capture, " rest\n")
          fixture.consolidation.end()
          fixture.outputWithoutSummaryLine shouldBe Seq(
            "Consolidated tests:",
            "  [core.test]",
            "    Run 1:",
            "      [stdout] partial rest",
            "  *** CONSOLIDATED TESTS ABORTED ***",
            "    Starting worker 0 of fork group 1 of core.test failed: java.lang.IllegalStateException: claim log unreadable"
          )
        }
      }
    }

    "the worker started on a future fails after it wrote an unterminated line, as where Mill cancels the task" should {
      "leave the capture streams unended, so the line the worker's output pump goes on to finish is captured whole" in {
        withEventLogFile { file =>
          writeCompletedRun(file)
          val fixture = new WorkerRunFixture(file, opening)
          fixture.run {
            writeStdout(fixture.capture, "partial")
            Future.failed(new InterruptedException("sleep interrupted"))
          }
          writeStdout(fixture.capture, " rest\n")
          fixture.consolidation.end()
          fixture.outputWithoutSummaryLine shouldBe Seq(
            "Consolidated tests:",
            "  [core.test]",
            "    Run 1:",
            "      [stdout] partial rest",
            "  *** CONSOLIDATED TESTS ABORTED ***",
            "    Starting worker 0 of fork group 1 of core.test failed: java.util.concurrent.ExecutionException: Boxed Exception"
          )
        }
      }
    }

    "the worker ends normally after it wrote an unterminated line" should {
      "end the capture streams, capturing that line" in {
        withEventLogFile { file =>
          writeCompletedRun(file)
          val fixture = new WorkerRunFixture(file, opening)
          fixture.runAndEndConsolidation {
            writeStdout(fixture.capture, "partial")
            startedAProcess
          }
          fixture.outputWithoutSummaryLine shouldBe Seq(
            "[core.test]",
            "  Run 1:",
            "    [stdout] partial"
          )
        }
      }
    }

    "starting the worker throws before its event log exists" should {
      "attach what starting threw to the root and fail the consolidation" in {
        withEventLogFile { file =>
          val fixture = new WorkerRunFixture(file, opening)
          fixture.runAndEndConsolidation(throw new IllegalStateException("testargs already exists"))
          fixture.outputWithoutSummaryLine shouldBe Seq(
            "Consolidated tests:",
            "  *** CONSOLIDATED TESTS ABORTED ***",
            "    Starting worker 0 of fork group 1 of core.test failed: java.lang.IllegalStateException: testargs already exists"
          )
          fixture.consolidation.state shouldBe ConsolidationState.EndedFailed
        }
      }
    }

    "the worker started on a future fails after it started" should {
      "attach what it failed with to the root, naming the worker, and fail the consolidation" in {
        withEventLogFile { file =>
          val fixture = new WorkerRunFixture(file, opening)
          fixture.runAndEndConsolidation(Future.failed(new IllegalStateException("claim folder missing")))
          fixture.outputWithoutSummaryLine shouldBe Seq(
            "Consolidated tests:",
            "  *** CONSOLIDATED TESTS ABORTED ***",
            "    Starting worker 0 of fork group 1 of core.test failed: java.lang.IllegalStateException: claim folder missing"
          )
          fixture.consolidation.state shouldBe ConsolidationState.EndedFailed
        }
      }
    }

    "the worker started no process, as where it found its fork group's queue empty" should {
      "add neither a run nor a process that ended before any run, and leave the consolidation unfailed" in {
        withEventLogFile { file =>
          val fixture = new WorkerRunFixture(file, opening)
          fixture.runAndEndConsolidation(Future.successful(false))
          fixture.runsAdded.get shouldBe 0
          fixture.processesEndedBeforeAnyRun.get shouldBe 0
          fixture.consolidation.state shouldBe ConsolidationState.Ended
        }
      }
    }

    "the worker started a process in which no run existed" should {
      "add that process as one that ended before any run" in {
        withEventLogFile { file =>
          val fixture = new WorkerRunFixture(file, opening)
          fixture.runAndEndConsolidation(startedAProcess)
          fixture.processesEndedBeforeAnyRun.get shouldBe 1
          fixture.consolidation.state shouldBe ConsolidationState.EndedFailed
        }
      }
    }

    "the worker runs on a future" should {
      "read the event log the worker writes after it started, and add its run" in {
        withEventLogFile { file =>
          val fixture = new WorkerRunFixture(file, opening)
          val workerEnd = Promise[Boolean]()
          fixture.workerRun.start(workerEnd.future)
          writeCompletedRun(file)
          workerEnd.success(true)
          fixture.endWorker()
          fixture.consolidation.end()
          fixture.outputWithoutSummaryLine shouldBe Seq("[core.test]")
          fixture.processesEndedBeforeAnyRun.get shouldBe 0
          fixture.consolidation.state shouldBe ConsolidationState.Ended
        }
      }
    }

    "starting the first of two workers throws before its event log exists" should {
      "attach what starting threw to the root, naming the first worker, and still give the second worker's run to the consolidation" in {
        withEventLogFile { firstFile =>
          withEventLogFile { secondFile =>
            writeCompletedRun(secondFile)
            val consolidationUnderTest = new ConsolidationUnderTest
            val first = new WorkerRunFixture(firstFile, opening, "worker 0 of fork group 0 of core.test", consolidationUnderTest)
            val second = new WorkerRunFixture(secondFile, opening, "worker 0 of fork group 1 of core.test", consolidationUnderTest)
            first.run(throw new IllegalStateException("testargs already exists"))
            second.run {
              writeStdout(second.capture, "second\n")
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
      "rethrow it once the reader is abandoned, so the reader adds the worker as a process that ended before any run" in {
        withEventLogFile { file =>
          val fixture = new WorkerRunFixture(file, opening)
          a[StackOverflowError] should be thrownBy fixture.workerRun.start(throw new StackOverflowError("worker"))
          eventually(fixture.processesEndedBeforeAnyRun.get shouldBe 1)
        }
      }
    }

    "starting the worker throws a fatal error while the run's process, named in its event log, has not exited" should {
      "rethrow it without waiting for that process to exit" in {
        withEventLogFile { file =>
          writeCompletedRun(file)
          val fixture = new WorkerRunFixture(file, opening)
          fixture.processExited = false
          implicit val signaler: Signaler = ThreadSignaler
          failAfter(patienceConfig.timeout) {
            a[StackOverflowError] should be thrownBy fixture.workerRun.start(throw new StackOverflowError("worker"))
          }
          fixture.processExited = true
          fixture.awaitReaderEnd()
          fixture.runsAdded.get shouldBe 1
        }
      }
    }
  }

  "Starting each of two workers" when {
    "starting the second throws a fatal error" should {
      "rethrow it once the first worker's reader is abandoned, so that reader ends" in {
        withEventLogFile { firstFile =>
          withEventLogFile { secondFile =>
            val first = new WorkerRunFixture(firstFile, opening)
            val second = new WorkerRunFixture(secondFile, opening)
            a[StackOverflowError] should be thrownBy WorkerRun.startEach(Seq(first, second)) { fixture =>
              fixture.workerRun.start(if (fixture eq first) Promise[Boolean]().future else throw new StackOverflowError("worker"))
              fixture.workerRun
            }
            eventually(first.processesEndedBeforeAnyRun.get shouldBe 1)
          }
        }
      }
    }
  }

  "Ending each of two workers" when {
    "waiting on the first throws an InterruptedException, as where Mill cancels the task" should {
      "rethrow it once both workers' readers are abandoned, so both readers end" in {
        withEventLogFile { firstFile =>
          withEventLogFile { secondFile =>
            val first = new WorkerRunFixture(firstFile, opening)
            val second = new WorkerRunFixture(secondFile, opening)
            Seq(first, second).foreach(_.workerRun.start(Promise[Boolean]().future))
            an[InterruptedException] should be thrownBy WorkerRun.endEach(Seq(first.workerRun, second.workerRun)) { _ =>
              throw new InterruptedException("cancelled")
            }
            eventually(Seq(first, second).map(_.processesEndedBeforeAnyRun.get) shouldBe Seq(1, 1))
          }
        }
      }
    }
  }
}
