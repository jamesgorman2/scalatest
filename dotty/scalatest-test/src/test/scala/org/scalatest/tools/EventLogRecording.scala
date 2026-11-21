package org.scalatest.tools

import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.Filter
import org.scalatest.Reporter
import org.scalatest.ScalaTestStatefulStatus
import org.scalatest.Suite
import org.scalatest.SuiteRegistration
import org.scalatest.events.Event
import org.scalatest.events.TestEventLogDecoder
import org.scalatest.events.TestEventLogEvent
import org.scalatest.matchers.should.Matchers
import scala.jdk.CollectionConverters.*

private[tools] trait EventLogRecording { this: Matchers =>

  protected val waitLimitSeconds = 30L

  protected final class RecordingEventLog extends OutputStream {
    private val writesUnderWay = new AtomicInteger
    private val overlapped = new AtomicBoolean
    private val closed = new AtomicBoolean
    private val recorded = new ConcurrentLinkedQueue[String]

    def writes: Vector[String] = recorded.asScala.toVector
    def hadOverlappingWrites: Boolean = overlapped.get
    def isClosed: Boolean = closed.get

    override def write(byte: Int): Unit = write(Array(byte.toByte), 0, 1)

    override def write(bytes: Array[Byte], offset: Int, length: Int): Unit = {
      if (writesUnderWay.incrementAndGet() > 1) {
        overlapped.set(true)
      }
      try {
        // Long enough that writes made without a common lock overlap.
        Thread.sleep(1)
        recorded.add(new String(bytes, offset, length, StandardCharsets.UTF_8))
      } finally {
        writesUnderWay.decrementAndGet()
      }
    }

    override def close(): Unit = closed.set(true)
  }

  protected final class StartedTopLevelSuite(emitter: TestEventLogEmitter, suite: Suite, reporter: Reporter = EventLogRecording.passingNoEventOn) {
    private val runOfSuite = new ScalaTestStatefulStatus
    private val start = emitter.startOfTopLevelSuite(suite, Filter.default, reporter)

    val structureWrapper: Reporter = start.reporter

    val registration: SuiteRegistration = {
      var inItsRun = SuiteRegistration.none
      start.run {
        inItsRun = SuiteRegistration.inContext(suite)
        runOfSuite
      }
      inItsRun
    }

    def runningInItsContext[A](body: => A): A = registration.runningInItsContext(body)

    def complete(): Unit = runOfSuite.setCompleted()
  }

  protected final class RecordingReporter extends Reporter {
    private var received = Vector.empty[Event]
    def events: Vector[Event] = synchronized(received)
    def apply(event: Event): Unit = synchronized { received :+= event }
  }

  protected def joined(thread: Thread): Boolean = {
    thread.join(TimeUnit.SECONDS.toMillis(waitLimitSeconds))
    !thread.isAlive
  }

  protected def wholeLinesWrittenByThreadsAtOnce(threadCount: Int)(handOffOfThread: (TestEventLogEmitter, Int) => Unit): Vector[TestEventLogEvent] = {
    val eventLog = new RecordingEventLog
    val emitter = TestEventLogEmitter.appendingTo(eventLog)
    val start = new CountDownLatch(1)
    val threads = (1 to threadCount).map { thread =>
      new Thread(() => {
        start.await(waitLimitSeconds, TimeUnit.SECONDS)
        handOffOfThread(emitter, thread)
      })
    }
    threads.foreach(_.start())
    start.countDown()
    threads.map(joined) shouldBe Vector.fill(threadCount)(true)

    val writes = eventLog.writes
    eventLog.hadOverlappingWrites shouldBe false
    writes.filterNot(write => write.indexOf('\n') == write.length - 1) shouldBe Vector.empty
    writes.map(write => TestEventLogDecoder.decode(write.stripSuffix("\n")))
  }
}

private[tools] object EventLogRecording {

  val passingNoEventOn: Reporter = new Reporter {
    def apply(event: Event): Unit = ()
  }
}
