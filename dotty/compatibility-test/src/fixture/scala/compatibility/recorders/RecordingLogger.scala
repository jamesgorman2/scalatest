package compatibility.recorders

import sbt.testing.Logger

final class RecordingLogger(record: String => Unit) extends Logger {

  def ansiCodesSupported(): Boolean = true

  def error(message: String): Unit = recordLines("error", message)

  def warn(message: String): Unit = recordLines("warn", message)

  def info(message: String): Unit = recordLines("info", message)

  def debug(message: String): Unit = recordLines("debug", message)

  def trace(throwable: Throwable): Unit = record("trace: " + EventLine.of(throwable))

  private def recordLines(level: String, message: String): Unit =
    message.split("\r?\n", -1).foreach(line => record(level + ": " + line))
}
