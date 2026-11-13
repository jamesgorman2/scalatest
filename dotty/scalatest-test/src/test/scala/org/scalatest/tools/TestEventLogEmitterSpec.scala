package org.scalatest.tools

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Instant
import org.scalatest.ConfigMap
import org.scalatest.events.AlertProvided
import org.scalatest.events.Event
import org.scalatest.events.Ordinal
import org.scalatest.events.RunAborted
import org.scalatest.events.RunCompleted
import org.scalatest.events.RunOutcome
import org.scalatest.events.RunStarting
import org.scalatest.events.RunStopped
import org.scalatest.events.TestEventLogEvent
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class TestEventLogEmitterSpec extends AnyWordSpec with Matchers {

  private val ordinal = new Ordinal(1)
  private val timeStamp = 1700000000000L
  private val identity = Right(ProcessIdentity(4242L, Instant.parse("2026-01-02T03:04:05.678Z")))

  private val runStartedLine = """{"item":"runStarted","pid":4242,"startTime":"2026-01-02T03:04:05.678Z"}"""
  private val runStartingLine =
    """{"item":"event","event":"RunStarting","testCount":3,"configMap":{},"threadName":"main","timeStamp":1700000000000}"""

  private def runStarting: Event = RunStarting(ordinal, 3, ConfigMap.empty, threadName = "main", timeStamp = timeStamp)

  private def runCompleted: Event = RunCompleted(ordinal, threadName = "main", timeStamp = timeStamp)

  private def alert(message: String, threadName: String): Event =
    AlertProvided(ordinal, message, None, threadName = threadName, timeStamp = timeStamp)

  private def runEndedLine(outcome: String): String = s"""{"item":"runEnded","outcome":"$outcome"}"""

  private def closingEventLine(eventName: String, fields: String): String =
    s"""{"item":"event","event":"$eventName",$fields"threadName":"main","timeStamp":1700000000000}"""

  private def linesAfterHandingOff(events: Event*): Vector[String] = {
    TemporaryEventLog.in { eventLogFile =>
      val emitter = TestEventLogEmitter.started(eventLogFile, identity)
      events.foreach(emitter.runEvent)
      emitter.runEvent(runCompleted)
      TemporaryEventLog.lines(eventLogFile)
    }
  }

  private final class ThrowsFromToString {
    override def toString: String = throw new IllegalStateException("no text")
  }

  "A started emitter" should {
    "have written the run's PID and start time as the event log's one line" in {
      TemporaryEventLog.in { eventLogFile =>
        val emitter = TestEventLogEmitter.started(eventLogFile, identity)
        try {
          TemporaryEventLog.lines(eventLogFile) shouldBe Vector(runStartedLine)
        } finally {
          emitter.runEvent(runCompleted)
        }
      }
    }

    "leave the event log file as the only file of its folder" in {
      TemporaryEventLog.in { eventLogFile =>
        val emitter = TestEventLogEmitter.started(eventLogFile, identity)
        try {
          TemporaryEventLog.namesIn(eventLogFile.getParent) shouldBe Set("events.log")
        } finally {
          emitter.runEvent(runCompleted)
        }
      }
    }

    "replace a file an earlier run left under the event log's name" in {
      TemporaryEventLog.in { eventLogFile =>
        Files.write(eventLogFile, "an earlier run's log\n".getBytes(StandardCharsets.UTF_8))
        val emitter = TestEventLogEmitter.started(eventLogFile, identity)
        try {
          TemporaryEventLog.lines(eventLogFile) shouldBe Vector(runStartedLine)
        } finally {
          emitter.runEvent(runCompleted)
        }
      }
    }
  }

  "An emitter" when {
    "its process has no identity" should {
      "fail to start, saying why, and create no file" in {
        TemporaryEventLog.in { eventLogFile =>
          the[IllegalStateException] thrownBy TestEventLogEmitter.started(eventLogFile, Left("this JVM has no java.lang.ProcessHandle")) should
            have message s"Cannot write the test event log $eventLogFile: this JVM has no java.lang.ProcessHandle"
          TemporaryEventLog.namesIn(eventLogFile.getParent) shouldBe Set.empty
        }
      }
    }

    "its event log's folder does not exist" should {
      "fail to start" in {
        TemporaryEventLog.in { eventLogFile =>
          val inMissingFolder: Path = eventLogFile.resolveSibling("missing").resolve("events.log")
          a[NoSuchFileException] should be thrownBy TestEventLogEmitter.started(inMissingFolder, identity)
        }
      }
    }

    "handed a run event" should {
      "append it as one line assigned to the run" in {
        TemporaryEventLog.in { eventLogFile =>
          val emitter = TestEventLogEmitter.started(eventLogFile, identity)
          try {
            emitter.runEvent(runStarting)
            TemporaryEventLog.lines(eventLogFile) shouldBe Vector(runStartedLine, runStartingLine)
          } finally {
            emitter.runEvent(runCompleted)
          }
        }
      }
    }

    "handed the event that closes the run" should {
      "write that the run ended completed after a RunCompleted" in {
        linesAfterHandingOff() shouldBe Vector(runStartedLine, closingEventLine("RunCompleted", ""), runEndedLine("completed"))
      }

      "write that the run ended stopped after a RunStopped" in {
        linesAfterHandingOff(RunStopped(ordinal, threadName = "main", timeStamp = timeStamp)) shouldBe
          Vector(runStartedLine, closingEventLine("RunStopped", ""), runEndedLine("stopped"))
      }

      "write that the run ended aborted after a RunAborted" in {
        linesAfterHandingOff(RunAborted(ordinal, "no suites", None, threadName = "main", timeStamp = timeStamp)) shouldBe
          Vector(runStartedLine, closingEventLine("RunAborted", """"message":"no suites","""), runEndedLine("aborted"))
      }

      "write nothing handed to it afterwards" in {
        TemporaryEventLog.in { eventLogFile =>
          val emitter = TestEventLogEmitter.started(eventLogFile, identity)
          emitter.runEvent(runCompleted)
          emitter.runEvent(alert("late", "main"))
          emitter.runEvent(runCompleted)
          TemporaryEventLog.lines(eventLogFile) shouldBe
            Vector(runStartedLine, closingEventLine("RunCompleted", ""), runEndedLine("completed"))
        }
      }
    }

    "handed an event it cannot encode" should {
      "not throw, and end the event log before that event" in {
        TemporaryEventLog.in { eventLogFile =>
          val emitter = TestEventLogEmitter.started(eventLogFile, identity)
          emitter.runEvent(runStarting)
          emitter.runEvent(RunStarting(ordinal, 3, ConfigMap("key" -> new ThrowsFromToString), threadName = "main", timeStamp = timeStamp))
          emitter.runEvent(runCompleted)
          TemporaryEventLog.lines(eventLogFile) shouldBe Vector(runStartedLine, runStartingLine)
        }
      }
    }

    "handed events by several threads at once" should {
      "write each as a whole line, and each thread's in the order that thread handed them off" in {
        val threadCount = 8
        val eventsOfEachThread = 200
        val decoded = TemporaryEventLog.in { eventLogFile =>
          val emitter = TestEventLogEmitter.started(eventLogFile, identity)
          val threads = (1 to threadCount).map { thread =>
            new Thread(() => (1 to eventsOfEachThread).foreach(event => emitter.runEvent(alert(s"alert $event", s"thread $thread"))))
          }
          threads.foreach(_.start())
          threads.foreach(_.join())
          emitter.runEvent(runCompleted)
          TemporaryEventLog.decoded(eventLogFile)
        }
        val messagesByThread =
          decoded.collect { case Right(TestEventLogEvent.ScalaTestEvent(None, alert: org.scalatest.events.DecodedEvent.AlertProvided)) =>
            alert.threadName -> alert.message
          }.groupMap((threadName, _) => threadName)((_, message) => message)
        decoded.filter(_.isLeft) shouldBe Vector.empty
        messagesByThread shouldBe
          (1 to threadCount).map(thread => s"thread $thread" -> (1 to eventsOfEachThread).map(event => s"alert $event").toVector).toMap
      }
    }

    "handed an event by a thread that has been interrupted" should {
      "write that event and those handed to it afterwards" in {
        TemporaryEventLog.in { eventLogFile =>
          val emitter = TestEventLogEmitter.started(eventLogFile, identity)
          val interrupted = new Thread(() => {
            Thread.currentThread.interrupt()
            emitter.runEvent(alert("while interrupted", "main"))
          })
          interrupted.start()
          interrupted.join()
          emitter.runEvent(runCompleted)
          TemporaryEventLog.decoded(eventLogFile).collect { case Right(TestEventLogEvent.RunEnded(outcome)) => outcome } shouldBe
            Vector(RunOutcome.Completed)
          TemporaryEventLog.lines(eventLogFile).count(_.contains("while interrupted")) shouldBe 1
        }
      }
    }
  }
}
