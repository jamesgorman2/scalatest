package org.scalatest.tools

import java.lang.management.ManagementFactory
import java.time.Instant
import java.util.Optional
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

// Stand-ins for java.lang.ProcessHandle and ProcessHandle.Info, read by method name as the real ones are.
// Each companion's `current` is a static method of its class, as ProcessHandle.current is.
private[tools] final class StartedProcessHandle {
  def pid(): Long = 4242L
  def info(): StartedProcessInfo = new StartedProcessInfo
}

private[tools] object StartedProcessHandle {
  def current(): StartedProcessHandle = new StartedProcessHandle
}

private[tools] final class StartedProcessInfo {
  def startInstant(): Optional[Instant] = Optional.of(Instant.parse("2026-01-02T03:04:05.678Z"))
}

private[tools] final class StartlessProcessHandle {
  def pid(): Long = 4242L
  def info(): StartlessProcessInfo = new StartlessProcessInfo
}

private[tools] object StartlessProcessHandle {
  def current(): StartlessProcessHandle = new StartlessProcessHandle
}

private[tools] final class StartlessProcessInfo {
  def startInstant(): Optional[Instant] = Optional.empty[Instant]
}

final class ProcessIdentitySpec extends AnyWordSpec with Matchers {

  private def loading(handle: Class[?], info: Class[?])(className: String): Class[?] = {
    className match {
      case "java.lang.ProcessHandle" => handle
      case "java.lang.ProcessHandle$Info" => info
      case other => throw new ClassNotFoundException(other)
    }
  }

  "A process's identity" when {
    "the JVM has ProcessHandle and the process a start time" should {
      "be the PID and start time the handle gives" in {
        ProcessIdentity.readThrough(loading(classOf[StartedProcessHandle], classOf[StartedProcessInfo])) shouldBe
          Right(ProcessIdentity(4242L, Instant.parse("2026-01-02T03:04:05.678Z")))
      }
    }

    "the process has no start time" should {
      "be absent, for that reason" in {
        ProcessIdentity.readThrough(loading(classOf[StartlessProcessHandle], classOf[StartlessProcessInfo])) shouldBe
          Left("this process has no start time")
      }
    }

    "the JVM has no ProcessHandle" should {
      "be absent, for that reason" in {
        ProcessIdentity.readThrough(className => throw new ClassNotFoundException(className)) shouldBe
          Left("this JVM has no java.lang.ProcessHandle")
      }
    }

    "read from this JVM" should {
      "hold the PID the JVM's management bean names" in {
        val identity = ProcessIdentity.current
        assume(identity.isRight, "this JVM has no ProcessHandle")
        val pidAtHost = ManagementFactory.getRuntimeMXBean.getName
        identity.map(_.pid.toString) shouldBe Right(pidAtHost.takeWhile(_ != '@'))
      }
    }
  }
}
