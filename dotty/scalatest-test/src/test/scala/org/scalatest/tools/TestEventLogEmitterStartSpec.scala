package org.scalatest.tools

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import org.scalatest.events.Event
import org.scalatest.events.RunAborted
import org.scalatest.events.RunOutcome
import org.scalatest.events.RunStopped
import org.scalatest.events.TestEventLogEvent
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.tools.TestEventLogEmitterSpec.*

final class TestEventLogEmitterStartSpec extends AnyFreeSpec with Matchers {

  private def linesAfterHandingOff(events: Event*): Vector[String] = {
    TemporaryEventLog.in { eventLogFile =>
      val emitter = TestEventLogEmitter.started(eventLogFile, identity)
      events.foreach(emitter.writeRunEvent)
      emitter.writeRunEvent(runCompleted)
      TemporaryEventLog.lines(eventLogFile)
    }
  }

  "An emitter started on the file system" - {
    "has written the run's PID and start time as the event log's one line" in {
      TemporaryEventLog.in { eventLogFile =>
        val emitter = TestEventLogEmitter.started(eventLogFile, identity)
        try {
          TemporaryEventLog.lines(eventLogFile) shouldBe Vector(runStartedLine)
        } finally {
          emitter.writeRunEvent(runCompleted)
        }
      }
    }

    "leaves the event log file as the only file of its folder" in {
      TemporaryEventLog.in { eventLogFile =>
        val emitter = TestEventLogEmitter.started(eventLogFile, identity)
        try {
          TemporaryEventLog.namesIn(eventLogFile.getParent) shouldBe Set("events.log")
        } finally {
          emitter.writeRunEvent(runCompleted)
        }
      }
    }

    "replaces a file an earlier run left under the event log's name" in {
      TemporaryEventLog.in { eventLogFile =>
        Files.write(eventLogFile, "an earlier run's log\n".getBytes(StandardCharsets.UTF_8))
        val emitter = TestEventLogEmitter.started(eventLogFile, identity)
        try {
          TemporaryEventLog.lines(eventLogFile) shouldBe Vector(runStartedLine)
        } finally {
          emitter.writeRunEvent(runCompleted)
        }
      }
    }

    "replaces a file an earlier run left under the name of the file it moves to the event log's name" in {
      TemporaryEventLog.in { eventLogFile =>
        Files.write(eventLogFile.resolveSibling("events.log.tmp"), "an earlier run's start\n".getBytes(StandardCharsets.UTF_8))
        val emitter = TestEventLogEmitter.started(eventLogFile, identity)
        try {
          (TemporaryEventLog.namesIn(eventLogFile.getParent), TemporaryEventLog.lines(eventLogFile)) shouldBe ((Set("events.log"), Vector(runStartedLine)))
        } finally {
          emitter.writeRunEvent(runCompleted)
        }
      }
    }

    "when handed a run event" - {
      "appends it as one line assigned to the run" in {
        TemporaryEventLog.in { eventLogFile =>
          val emitter = TestEventLogEmitter.started(eventLogFile, identity)
          try {
            emitter.writeRunEvent(runStarting)
            TemporaryEventLog.lines(eventLogFile) shouldBe Vector(runStartedLine, runStartingLine)
          } finally {
            emitter.writeRunEvent(runCompleted)
          }
        }
      }
    }

    "when handed the event that closes the run" - {
      "writes that the run ended completed after a RunCompleted" in {
        linesAfterHandingOff() shouldBe Vector(runStartedLine, eventLine("RunCompleted", ""), TestEventLogLine.runEnded("completed"))
      }

      "writes that the run ended stopped after a RunStopped" in {
        linesAfterHandingOff(RunStopped(ordinal, threadName = "main", timeStamp = timeStamp)) shouldBe
          Vector(runStartedLine, eventLine("RunStopped", ""), TestEventLogLine.runEnded("stopped"))
      }

      "writes that the run ended aborted after a RunAborted" in {
        linesAfterHandingOff(RunAborted(ordinal, "no suites", None, threadName = "main", timeStamp = timeStamp)) shouldBe
          Vector(runStartedLine, eventLine("RunAborted", """"message":"no suites","""), TestEventLogLine.runEnded("aborted"))
      }

      "writes nothing handed to it afterwards" in {
        TemporaryEventLog.in { eventLogFile =>
          val emitter = TestEventLogEmitter.started(eventLogFile, identity)
          emitter.writeRunEvent(runCompleted)
          emitter.writeRunEvent(alertProvided("late", "main"))
          emitter.writeRunEvent(runCompleted)
          TemporaryEventLog.lines(eventLogFile) shouldBe
            Vector(runStartedLine, eventLine("RunCompleted", ""), TestEventLogLine.runEnded("completed"))
        }
      }
    }

    "when handed an event it cannot encode" - {
      "does not throw, and ends the event log before that event" in {
        TemporaryEventLog.in { eventLogFile =>
          val emitter = TestEventLogEmitter.started(eventLogFile, identity)
          emitter.writeRunEvent(runStarting)
          emitter.writeRunEvent(cannotBeEncodedFor(new IllegalStateException("no text")))
          emitter.writeRunEvent(runCompleted)
          TemporaryEventLog.lines(eventLogFile) shouldBe Vector(runStartedLine, runStartingLine)
        }
      }
    }

    "when handed an event by a thread that has been interrupted" - {
      "writes that event and those handed to it afterwards" in {
        TemporaryEventLog.in { eventLogFile =>
          val emitter = TestEventLogEmitter.started(eventLogFile, identity)
          val interrupted = new Thread(() => {
            Thread.currentThread.interrupt()
            emitter.writeRunEvent(alertProvided("while interrupted", "main"))
          })
          interrupted.start()
          interrupted.join()
          emitter.writeRunEvent(runCompleted)
          TemporaryEventLog.decoded(eventLogFile).collect { case TestEventLogEvent.RunEnded(outcome) => outcome } shouldBe
            Vector(RunOutcome.Completed)
          TemporaryEventLog.lines(eventLogFile).count(_.contains("while interrupted")) shouldBe 1
        }
      }
    }
  }

  "An emitter fails to start" - {
    "and creates no event log file, where it cannot write the file it moves to the event log's name" in {
      TemporaryEventLog.in { eventLogFile =>
        Files.createDirectory(eventLogFile.resolveSibling("events.log.tmp"))
        an[IOException] should be thrownBy TestEventLogEmitter.started(eventLogFile, identity)
        TemporaryEventLog.namesIn(eventLogFile.getParent) shouldBe Set("events.log.tmp")
      }
    }

    "without writing to the link's target, where it finds a link under the name of the file it moves to the event log's name" in {
      TemporaryEventLog.in { eventLogFile =>
        val target = Files.write(eventLogFile.resolveSibling("target"), "the target's own content\n".getBytes(StandardCharsets.UTF_8))
        try Files.createSymbolicLink(eventLogFile.resolveSibling("events.log.tmp"), target)
        catch { case notPermitted: IOException => cancel(s"This user cannot create a symbolic link: $notPermitted") }
        an[IOException] should be thrownBy TestEventLogEmitter.started(eventLogFile, identity)
        (TemporaryEventLog.namesIn(eventLogFile.getParent), Files.readString(target)) shouldBe ((Set("events.log.tmp", "target"), "the target's own content\n"))
      }
    }

    "saying why, and creates no file, where its process has no identity" in {
      TemporaryEventLog.in { eventLogFile =>
        val noProcessHandle = new IllegalStateException("this JVM has no java.lang.ProcessHandle")
        the[IllegalStateException] thrownBy TestEventLogEmitter.startedIfGiven(Some(eventLogFile.toString), throw noProcessHandle) should
          have message s"Cannot write the test event log $eventLogFile: this JVM has no java.lang.ProcessHandle"
        TemporaryEventLog.namesIn(eventLogFile.getParent) shouldBe Set.empty
      }
    }

    "where its event log's folder does not exist" in {
      TemporaryEventLog.in { eventLogFile =>
        val inMissingFolder: Path = eventLogFile.resolveSibling("missing").resolve("events.log")
        a[NoSuchFileException] should be thrownBy TestEventLogEmitter.started(inMissingFolder, identity)
      }
    }
  }
}
