package org.scalatest.tools

import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.Reporter
import org.scalatest.events.Event
import org.scalatest.events.TestEventLogDecoder
import org.scalatest.events.TestEventLogEvent
import org.scalatest.matchers.should.Matchers
import scala.jdk.CollectionConverters.*

/** An event log that records each write made to it, a reporter that records its events, and threads that hand an emitter their events at once. */
private[tools] trait EventLogRecording { this: Matchers =>

  protected val waitLimitSeconds = 30L

  // Each write takes long enough that writes made without a common lock overlap.
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
        Thread.sleep(1)
        recorded.add(new String(bytes, offset, length, StandardCharsets.UTF_8))
      } finally {
        writesUnderWay.decrementAndGet()
      }
    }

    override def close(): Unit = closed.set(true)
  }

  protected final class RecordingReporter extends Reporter {
    private var received = Vector.empty[Event]
    def events: Vector[Event] = received
    def apply(event: Event): Unit = received :+= event
  }

  protected def joined(thread: Thread): Boolean = {
    thread.join(TimeUnit.SECONDS.toMillis(waitLimitSeconds))
    !thread.isAlive
  }

  /**
   * Has each of several threads hand an emitter its events from a common start, and returns the events the event
   * log then holds. Each must have been written as one whole line, in one write that overlaps no other.
   */
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
    val decoded = writes.map(write => TestEventLogDecoder.decode(write.stripSuffix("\n")))
    decoded.filter(_.isLeft) shouldBe Vector.empty
    decoded.collect { case Right(event) => event }
  }
}
