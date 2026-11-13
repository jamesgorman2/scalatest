package org.scalatest.tools

import java.time.Instant
import java.util.Optional

private[scalatest] final case class ProcessIdentity(pid: Long, startTime: Instant)

private[scalatest] object ProcessIdentity {

  /** Returns this process's PID and start time as `ProcessHandle.current()` gives them, or why it gives none. */
  def current: Either[String, ProcessIdentity] = readThrough(Class.forName(_))

  // Reads through reflection because ProcessHandle is absent on Java 8, which core still compiles on. Each method is
  // looked up on the two public interfaces: the handle's runtime class is not accessible.
  def readThrough(loadClass: String => Class[?]): Either[String, ProcessIdentity] = {
    try {
      val handleInterface = loadClass("java.lang.ProcessHandle")
      val infoInterface = loadClass("java.lang.ProcessHandle$Info")
      val handle = handleInterface.getMethod("current").invoke(null)
      val pid = handleInterface.getMethod("pid").invoke(handle).asInstanceOf[java.lang.Long]
      val info = handleInterface.getMethod("info").invoke(handle)
      val startTime = infoInterface.getMethod("startInstant").invoke(info).asInstanceOf[Optional[Instant]]
      if (startTime.isPresent) {
        Right(ProcessIdentity(pid, startTime.get))
      } else {
        Left("this process has no start time")
      }
    } catch {
      case _: ClassNotFoundException => Left("this JVM has no java.lang.ProcessHandle")
    }
  }
}
