package org.scalatest.tools

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.tools.TestEventLogEmitterSpec.identity
import org.scalatest.tools.TestEventLogEmitterSpec.runCompleted
import org.scalatest.tools.TestEventLogEmitterSpec.runStartedLine

final class TestEventLogEmitterStartSpec extends AnyFreeSpec with Matchers {

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
