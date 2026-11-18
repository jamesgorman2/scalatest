package org.scalatest.tools

import org.scalatest.exceptions.PropertyCheckFailedException
import org.scalatest.exceptions.StackDepth

/**
 * What <code>StringReporter</code>'s formatting reads of a throwable, so that it can format one that is no longer a
 * live <code>Throwable</code>.
 *
 * Two views of one throwable must be equal, and views of different throwables unequal: the formatting stops at a
 * throwable its cause chain has already shown.
 */
private[scalatest] trait ThrowableView {

  def className: String

  def message: Option[String]

  /** Defined only for a <code>StackDepth</code>. */
  def stackDepth: Option[StackDepthView]

  def isPropertyCheckFailure: Boolean

  /** Each stack frame as <code>StackTraceElement.toString</code> gives it. */
  def frames: IndexedSeq[String]

  def cause: Option[ThrowableView]
}

private[scalatest] trait StackDepthView {

  def fileNameAndLineNumber: Option[String]

  def filePathnameAndLineNumber: Option[String]

  /** The index of the failed code's frame, where it is known. */
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
