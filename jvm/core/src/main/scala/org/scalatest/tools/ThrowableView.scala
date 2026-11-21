package org.scalatest.tools

import org.scalatest.exceptions.PropertyCheckFailedException
import org.scalatest.exceptions.StackDepth

private[scalatest] trait ThrowableView {

  def className: String

  def message: Option[String]

  def stackDepth: Option[StackDepthView]

  def isPropertyCheckFailure: Boolean

  def frames: IndexedSeq[String]

  def cause: Option[ThrowableView]
}

private[scalatest] trait StackDepthView {

  def fileNameAndLineNumber: Option[String]

  def filePathnameAndLineNumber: Option[String]

  def failedCodeStackDepth: Option[Int]
}

private[scalatest] object ThrowableView {

  def of(throwable: Throwable): ThrowableView = new LiveThrowable(throwable)

  private final class LiveThrowable(private val throwable: Throwable) extends ThrowableView {

    def className: String = throwable.getClass.getName

    def message: Option[String] = Option(throwable.getMessage)

    def stackDepth: Option[StackDepthView] =
      throwable match {
        case failed: StackDepth => Some(new LiveStackDepth(failed))
        case _ => None
      }

    def isPropertyCheckFailure: Boolean = throwable.isInstanceOf[PropertyCheckFailedException]

    def frames: IndexedSeq[String] = throwable.getStackTrace.toIndexedSeq.map(_.toString)

    def cause: Option[ThrowableView] = Option(throwable.getCause).map(of)

    // Equal only to a view of the same throwable, so that StringReporter stops a cause chain at a throwable it has
    // already shown.
    override def equals(other: Any): Boolean =
      other match {
        case live: LiveThrowable => live.throwable eq throwable
        case _ => false
      }

    override def hashCode: Int = System.identityHashCode(throwable)
  }

  private final class LiveStackDepth(failed: StackDepth) extends StackDepthView {

    def fileNameAndLineNumber: Option[String] = failed.failedCodeFileNameAndLineNumberString

    def filePathnameAndLineNumber: Option[String] = failed.failedCodeFilePathnameAndLineNumberString

    def failedCodeStackDepth: Option[Int] = Some(failed.failedCodeStackDepth)
  }
}
