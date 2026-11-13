package org.scalatest.tools

// Stack frames that are the same on every machine and JDK, for throwables whose stack traces a test compares.
private[tools] object StackFrames {

  def frames(className: String, count: Int): Array[StackTraceElement] =
    Array.tabulate(count)(index => new StackTraceElement(className, "method" + index, className + ".scala", 10 + index))

  def withFrames[T <: Throwable](throwable: T, className: String, count: Int): T = {
    throwable.setStackTrace(frames(className, count))
    throwable
  }
}
