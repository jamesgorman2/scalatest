package org.scalatest.mill

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.Signaler
import org.scalatest.concurrent.ThreadSignaler
import org.scalatest.concurrent.TimeLimits
import org.scalatest.consolidation.Consolidation
import org.scalatest.consolidation.ConsolidationState
import org.scalatest.consolidation.ModuleName
import org.scalatest.consolidation.RunFeed
import org.scalatest.matchers.should
import org.scalatest.mill.EventLogs.*
import org.scalatest.mill.WorkerOutput.writeStdout
import org.scalatest.time.Seconds
import org.scalatest.time.Span
import org.scalatest.wordspec.AnyWordSpec
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*

final class WorkerRunSpec extends AnyWordSpec with should.Matchers with Eventually with TimeLimits {

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(5, Seconds))

  private val module: ModuleName = ModuleName("core.test")
  private val workerEndTimeout: FiniteDuration = 30.seconds

  private final class WorkerRunFixture(
      val eventLogFile: EventLogFile,
      workerName: String = "worker 0 of fork group 1 of core.test",
      consolidationUnderTest: ConsolidationUnderTest = new ConsolidationUnderTest
  ) {
    val consolidation: Consolidation = consolidationUnderTest.consolidation
    val capture: WorkerOutputCapture = new WorkerOutputCapture(StandardCharsets.UTF_8)
    val counting: CountingConsolidation = new CountingConsolidation(consolidation, module)
    @volatile var processExited: Boolean = true
    private val reader = new EventLogReader(eventLogFile, addCapturedRun, () => counting.addProcessEndedBeforeAnyRun(), _ => processExited, pollInterval = 1.millis)
    val workerRun: WorkerRun = new WorkerRun(consolidation, workerName, reader, capture)

    def runAndEndConsolidation(startWorker: => Future[WorkerProcess]): Unit = {
      run(startWorker)
      consolidation.end()
    }

    def run(startWorker: => Future[WorkerProcess]): Unit = {
      start(startWorker)
      endWorker()
    }

    @volatile private var workerEnd: Option[Future[WorkerProcess]] = None

    def start(startWorker: => Future[WorkerProcess]): Unit =
      workerRun.start { millStartsWorker =>
        millStartsWorker()
        recordingWorkerEnd(startWorker)
      }

    def startWithoutMillStartingIt(startWorker: => Future[WorkerProcess]): Unit = workerRun.start(_ => recordingWorkerEnd(startWorker))

    def startWithMillStartingItLater(startWorker: => Future[WorkerProcess]): () => Unit = {
      val millStartsWorkerLater = new AtomicReference[() => Unit](() => ())
      workerRun.start { millStartsWorker =>
        millStartsWorkerLater.set(millStartsWorker)
        recordingWorkerEnd(startWorker)
      }
      millStartsWorkerLater.get()
    }

    def endWorker(): Unit = {
      awaitWorkerEnd()
      workerRun.end(_ => ())
    }

    def awaitWorkerEnd(): Unit =
      // `WorkerRun.end` turns a throw from its wait into a root failure, so the test thread waits first, failing the
      // test if the worker never ends.
      workerEnd.foreach(Await.ready(_, workerEndTimeout))

    def awaitReaderEnd(): Unit = reader.awaitStreamEnd()

    def outputWithoutSummaryLine: Seq[String] = consolidationUnderTest.outputWithoutSummaryLine

    private def recordingWorkerEnd(startWorker: => Future[WorkerProcess]): Future[WorkerProcess] = {
      val started = startWorker
      workerEnd = Some(started)
      started
    }

    private def addCapturedRun(readRun: RunFeed => Unit): Unit =
      counting.addRun { feed =>
        capture.addTo(feed.add)
        readRun(feed)
      }
  }

  private def readerThreadsOf(fixture: WorkerRunFixture): Int =
    Thread.getAllStackTraces.keySet.asScala.count(thread => thread.getName == EventLogReader.threadName(fixture.eventLogFile) && thread.isAlive)

  private def withWorkerRun[A](test: (Path, WorkerRunFixture) => A): A =
    withEventLogFile(file => test(file, new WorkerRunFixture(EventLogFile.at(file))))

  private def withTwoEventLogFiles[A](test: (Path, Path) => A): A =
    withEventLogFile(firstFile => withEventLogFile(secondFile => test(firstFile, secondFile)))

  private def withTwoWorkerRuns[A](test: (WorkerRunFixture, WorkerRunFixture) => A): A =
    withTwoEventLogFiles((firstFile, secondFile) => test(new WorkerRunFixture(EventLogFile.at(firstFile)), new WorkerRunFixture(EventLogFile.at(secondFile))))

  private val startedAProcess: Future[WorkerProcess] = Future.successful(WorkerProcess.Started)

  private def unendingWorker: Future[WorkerProcess] = Promise[WorkerProcess]().future

  "Running a worker" when {
    "its event log file never becomes readable" should {
      "attach what reading threw to the root, end the run's event stream and fail the consolidation" in {
        withEventLogFile { file =>
          appendCompletedRun(file)
          val fixture = new WorkerRunFixture(neverOpening(file))
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

    "a line of its event log cannot be decoded" should {
      "add a root failure naming the line and why, end the run's event stream and fail the consolidation" in {
        withWorkerRun { (file, fixture) =>
          append(file, s"$runStarted\nnot an event\n$runEndedCompleted\n")
          fixture.processExited = false
          fixture.runAndEndConsolidation(startedAProcess)
          fixture.outputWithoutSummaryLine shouldBe Seq(
            "Consolidated tests:",
            "  [core.test]",
            "  *** CONSOLIDATED TESTS ABORTED ***",
            "    Reading the event log of worker 0 of fork group 1 of core.test failed: org.scalatest.events.MalformedTestEventLogException: no value starts at offset 0, in not an event"
          )
          fixture.consolidation.state shouldBe ConsolidationState.EndedFailed
        }
      }
    }

    "starting the worker throws after the worker wrote an unterminated line" should {
      "leave the capture streams open, so the line the worker's output pump goes on to finish is captured whole" in {
        withWorkerRun { (file, fixture) =>
          appendCompletedRun(file)
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
      "leave the capture streams open, so the line the worker's output pump goes on to finish is captured whole" in {
        withWorkerRun { (file, fixture) =>
          appendCompletedRun(file)
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
            "    Starting worker 0 of fork group 1 of core.test failed: java.lang.InterruptedException: sleep interrupted"
          )
        }
      }
    }

    "the worker ends normally after it wrote an unterminated line" should {
      "end the capture streams, capturing that line" in {
        withWorkerRun { (file, fixture) =>
          appendCompletedRun(file)
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
        withWorkerRun { (_, fixture) =>
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
        withWorkerRun { (_, fixture) =>
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
        withWorkerRun { (_, fixture) =>
          fixture.runAndEndConsolidation(Future.successful(WorkerProcess.NotStarted))
          fixture.counting.runsAdded shouldBe 0
          fixture.counting.processesEndedBeforeAnyRun shouldBe 0
          fixture.consolidation.state shouldBe ConsolidationState.Ended
        }
      }
    }

    "the worker started a process in which no run existed" should {
      "add that process as one that ended before any run" in {
        withWorkerRun { (_, fixture) =>
          fixture.runAndEndConsolidation(startedAProcess)
          fixture.counting.processesEndedBeforeAnyRun shouldBe 1
          fixture.consolidation.state shouldBe ConsolidationState.EndedFailed
        }
      }
    }

    "the worker runs on a future" should {
      "read the event log the worker writes after it started, and add its run" in {
        withWorkerRun { (file, fixture) =>
          val workerEnd = Promise[WorkerProcess]()
          fixture.start(workerEnd.future)
          appendCompletedRun(file)
          workerEnd.success(WorkerProcess.Started)
          fixture.endWorker()
          fixture.consolidation.end()
          fixture.outputWithoutSummaryLine shouldBe Seq("[core.test]")
          fixture.counting.processesEndedBeforeAnyRun shouldBe 0
          fixture.consolidation.state shouldBe ConsolidationState.Ended
        }
      }
    }

    "the worker started a process without starting its event log reader, as where it ran on the calling thread uncaptured" should {
      "read the worker's run once it has ended, and attach a failure naming the worker to the root" in {
        withWorkerRun { (file, fixture) =>
          appendCompletedRun(file)
          fixture.startWithoutMillStartingIt(startedAProcess)
          fixture.endWorker()
          fixture.consolidation.end()
          fixture.counting.runsAdded shouldBe 1
          fixture.outputWithoutSummaryLine shouldBe Seq(
            "Consolidated tests:",
            "  [core.test]",
            "  *** CONSOLIDATED TESTS ABORTED ***",
            "    Capturing the output of worker 0 of fork group 1 of core.test failed: the worker ran outside the test command's capture, so its output went to Mill's console"
          )
        }
      }
    }

    "starting the first of two workers throws before its event log exists" should {
      "attach what starting threw to the root, naming the first worker, and still give the second worker's run to the consolidation" in {
        withTwoEventLogFiles { (firstFile, secondFile) =>
          appendCompletedRun(secondFile)
          val consolidationUnderTest = new ConsolidationUnderTest
          val first = new WorkerRunFixture(EventLogFile.at(firstFile), "worker 0 of fork group 0 of core.test", consolidationUnderTest)
          val second = new WorkerRunFixture(EventLogFile.at(secondFile), "worker 0 of fork group 1 of core.test", consolidationUnderTest)
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

    "starting the worker throws a fatal error before its event log exists" should {
      "rethrow it once the reader is abandoned, so the reader adds the worker as a process that ended before any run" in {
        withWorkerRun { (_, fixture) =>
          a[StackOverflowError] should be thrownBy fixture.start(throw new StackOverflowError("worker"))
          eventually(fixture.counting.processesEndedBeforeAnyRun shouldBe 1)
        }
      }
    }

    "starting the worker throws a fatal error while the run's process, named in its event log, has not exited" should {
      "rethrow it without waiting for that process to exit" in {
        withWorkerRun { (file, fixture) =>
          appendCompletedRun(file)
          fixture.processExited = false
          implicit val signaler: Signaler = ThreadSignaler
          failAfter(patienceConfig.timeout) {
            a[StackOverflowError] should be thrownBy fixture.start(throw new StackOverflowError("worker"))
          }
          fixture.processExited = true
          fixture.awaitReaderEnd()
          fixture.counting.runsAdded shouldBe 1
        }
      }
    }
  }

  "Starting a worker" when {
    "Mill has not yet started it, as where the worker waits for a free job" should {
      "start no event log reader" in {
        withWorkerRun { (_, fixture) =>
          fixture.startWithoutMillStartingIt(unendingWorker)
          readerThreadsOf(fixture) shouldBe 0
          fixture.workerRun.abandon()
        }
      }
    }

    "Mill goes on to start it" should {
      "start its event log reader then, which adds the worker's run" in {
        withWorkerRun { (file, fixture) =>
          val workerEnd = Promise[WorkerProcess]()
          val millStartsWorker = fixture.startWithMillStartingItLater(workerEnd.future)
          millStartsWorker()
          readerThreadsOf(fixture) shouldBe 1
          appendCompletedRun(file)
          workerEnd.success(WorkerProcess.Started)
          fixture.endWorker()
          fixture.counting.runsAdded shouldBe 1
        }
      }
    }

    "it finds its fork group's queue empty and ends at once, before the test command waits for it" should {
      "end its event log reader then, adding neither a run nor a process that ended before any run" in {
        withWorkerRun { (_, fixture) =>
          val workerEnd = Promise[WorkerProcess]()
          fixture.start(workerEnd.future)
          readerThreadsOf(fixture) shouldBe 1
          workerEnd.success(WorkerProcess.NotStarted)
          eventually(readerThreadsOf(fixture) shouldBe 0)
          fixture.counting.runsAdded shouldBe 0
          fixture.counting.processesEndedBeforeAnyRun shouldBe 0
          fixture.endWorker()
        }
      }
    }

    "its process dies before any run, before the test command waits for it" should {
      "end its event log reader then, adding the process as one that ended before any run" in {
        withWorkerRun { (_, fixture) =>
          val workerEnd = Promise[WorkerProcess]()
          fixture.start(workerEnd.future)
          workerEnd.success(WorkerProcess.Started)
          eventually(readerThreadsOf(fixture) shouldBe 0)
          fixture.counting.processesEndedBeforeAnyRun shouldBe 1
          fixture.endWorker()
        }
      }
    }

    "it starts its event log reader then throws, as where it runs on the calling thread, before the test command waits for it" should {
      "end its event log reader then" in {
        withWorkerRun { (_, fixture) =>
          fixture.start(throw new IllegalStateException("claim log unreadable"))
          eventually(readerThreadsOf(fixture) shouldBe 0)
          fixture.endWorker()
        }
      }
    }

    "Mill starts it only once the test command has waited for it, as where Mill runs a cancelled task's body" should {
      "start no event log reader" in {
        withWorkerRun { (_, fixture) =>
          val millStartsWorker = fixture.startWithMillStartingItLater(Future.failed(new InterruptedException("cancelled")))
          fixture.endWorker()
          millStartsWorker()
          fixture.awaitReaderEnd()
          fixture.counting.processesEndedBeforeAnyRun shouldBe 0
        }
      }
    }

    "the worker ends without Mill having started it, as where Mill cancels the task first" should {
      "add neither a run nor a process that ended before any run" in {
        withWorkerRun { (_, fixture) =>
          fixture.startWithoutMillStartingIt(Future.failed(new InterruptedException("cancelled")))
          fixture.endWorker()
          fixture.counting.runsAdded shouldBe 0
          fixture.counting.processesEndedBeforeAnyRun shouldBe 0
        }
      }
    }
  }

  "Starting each of two workers" when {
    "Mill starts the first but not yet the second" should {
      "start the first worker's event log reader only" in {
        withTwoWorkerRuns { (first, second) =>
          def millStartingOnlyTheFirst(fixture: WorkerRunFixture): WorkerRun = {
            if (fixture eq first)
              fixture.start(unendingWorker)
            else
              fixture.startWithoutMillStartingIt(unendingWorker)
            fixture.workerRun
          }

          val workerRuns = WorkerRun.startEach(Seq(first, second))(millStartingOnlyTheFirst)
          Seq(readerThreadsOf(first), readerThreadsOf(second)) shouldBe Seq(1, 0)
          workerRuns.foreach(_.abandon())
          eventually(readerThreadsOf(first) shouldBe 0)
        }
      }
    }

    "starting the second throws a fatal error" should {
      "rethrow it once the first worker's reader is abandoned, so that reader ends" in {
        withTwoWorkerRuns { (first, second) =>
          def startingTheSecondFatally(fixture: WorkerRunFixture): WorkerRun = {
            fixture.start(if (fixture eq first) unendingWorker else throw new StackOverflowError("worker"))
            fixture.workerRun
          }

          a[StackOverflowError] should be thrownBy WorkerRun.startEach(Seq(first, second))(startingTheSecondFatally)
          eventually(first.counting.processesEndedBeforeAnyRun shouldBe 1)
        }
      }
    }
  }

  "Ending each worker" when {
    "a worker started on a future failed, and its process goes on writing after the consolidation has ended" should {
      "drop that output, throwing nothing" in {
        withWorkerRun { (file, fixture) =>
          appendCompletedRun(file)
          fixture.start(Future.failed(new InterruptedException("sleep interrupted")))
          fixture.awaitWorkerEnd()
          WorkerRun.endEach(Seq(fixture.workerRun))(_ => ())
          fixture.consolidation.end()
          noException should be thrownBy writeStdout(fixture.capture, "late\n")
        }
      }
    }
  }

  "Ending each of two workers" when {
    "the first fails after it started and the second finds its fork group's queue empty" should {
      "give the first as failed to start and the second as ended" in {
        withTwoWorkerRuns { (first, second) =>
          first.start(Future.failed(new IllegalStateException("claim folder missing")))
          second.start(Future.successful(WorkerProcess.NotStarted))
          Seq(first, second).foreach(_.awaitWorkerEnd())
          WorkerRun.endEach(Seq(first.workerRun, second.workerRun))(_ => ()) shouldBe Seq(WorkerRun.Ending.FailedToStart, WorkerRun.Ending.Ended)
        }
      }
    }

    "waiting on the first throws an InterruptedException, as where Mill cancels the task" should {
      "rethrow it once both workers' readers are abandoned, so both readers end" in {
        withTwoWorkerRuns { (first, second) =>
          Seq(first, second).foreach(_.start(unendingWorker))
          an[InterruptedException] should be thrownBy WorkerRun.endEach(Seq(first.workerRun, second.workerRun)) { _ =>
            throw new InterruptedException("cancelled")
          }
          eventually(Seq(first, second).map(_.counting.processesEndedBeforeAnyRun) shouldBe Seq(1, 1))
        }
      }
    }
  }
}
