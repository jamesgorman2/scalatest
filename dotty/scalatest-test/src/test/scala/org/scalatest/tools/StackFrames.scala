package org.scalatest.tools

private[tools] object StackFrames {

  def lineNumberOfFrame(index: Int): Int = 10 + index

  def frames(className: String, count: Int): Array[StackTraceElement] =
    Array.tabulate(count)(index => new StackTraceElement(className, "method" + index, className + ".scala", lineNumberOfFrame(index)))

  def withFrames[T <: Throwable](throwable: T, className: String, count: Int): T = {
    throwable.setStackTrace(frames(className, count))
    throwable
  }
}
