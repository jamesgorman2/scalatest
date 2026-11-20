package org.scalatest.tools

import java.lang.management.ManagementFactory
import java.time.Instant
import java.util.Optional
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

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
          ProcessIdentity(4242L, Instant.parse("2026-01-02T03:04:05.678Z"))
      }
    }

    "the process has no start time" should {
      "fail to be read, saying why" in {
        the[IllegalStateException] thrownBy
          ProcessIdentity.readThrough(loading(classOf[StartlessProcessHandle], classOf[StartlessProcessInfo])) should
          have message "this process has no start time"
      }
    }

    "the JVM has no ProcessHandle" should {
      "fail to be read, saying why" in {
        the[IllegalStateException] thrownBy ProcessIdentity.readThrough(className => throw new ClassNotFoundException(className)) should
          have message "this JVM has no java.lang.ProcessHandle"
      }
    }

    "read from this JVM" should {
      "hold the PID the JVM's management bean names" in {
        val identity = {
          try {
            ProcessIdentity.current
          } catch {
            case unreadable: IllegalStateException => cancel(unreadable.getMessage)
          }
        }
        val pidAtHost = ManagementFactory.getRuntimeMXBean.getName
        identity.pid.toString shouldBe pidAtHost.takeWhile(_ != '@')
      }
    }
  }
}
