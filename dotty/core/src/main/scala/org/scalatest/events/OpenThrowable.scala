package org.scalatest.events

import scala.annotation.tailrec

/**
 * A throwable whose cause and suppressed throwables, each given as a `Source`, are still being turned into `Linked`
 * values: its cause first, then each suppressed throwable in order.
 */
private[events] abstract class OpenThrowable[Source, Linked](cause: Option[Source], suppressed: Iterator[Source]) {

  // Each source is dropped once read. The closed value may hold this throwable until its links are first read, so this
  // throwable must not keep the sources reachable.
  private var unreadCause = cause
  private var unreadSuppressed = suppressed
  private var addsCause = false
  private var causeLinked: Option[Linked] = None
  private var suppressedLinked = Vector.empty[Linked]

  /** The throwable's value once each of its links has been added. */
  def closed: Linked

  protected def linkedCause: Option[Linked] = causeLinked

  protected def linkedSuppressed: Vector[Linked] = suppressedLinked

  private def nextLink(): Option[Source] = {
    addsCause = unreadCause.nonEmpty
    if (addsCause) {
      val next = unreadCause
      unreadCause = None
      next
    } else {
      val next = unreadSuppressed.nextOption()
      if (next.isEmpty) {
        unreadSuppressed = Iterator.empty
      }
      next
    }
  }

  private def add(linked: Linked): Unit = {
    if (addsCause) {
      causeLinked = Some(linked)
    } else {
      suppressedLinked :+= linked
    }
  }
}

private[events] object OpenThrowable {

  /**
   * Closes `outermost` and every throwable it links to. `linkedOrOpened` gives each link either its value, for a
   * throwable already seen, or a throwable to open.
   */
  // Keeps the throwables whose links are still to be added in a list, not on the call stack, so that a long chain of
  // causes cannot overflow the stack.
  def closedFrom[Source, Linked](outermost: OpenThrowable[Source, Linked])(
    linkedOrOpened: Source => Either[Linked, OpenThrowable[Source, Linked]]
  ): Linked = {
    @tailrec
    def closing(innermost: OpenThrowable[Source, Linked], outer: List[OpenThrowable[Source, Linked]]): Linked = {
      innermost.nextLink() match {
        case Some(source) =>
          linkedOrOpened(source) match {
            case Left(linked) =>
              innermost.add(linked)
              closing(innermost, outer)
            case Right(opened) => closing(opened, innermost :: outer)
          }
        case None =>
          val closed = innermost.closed
          outer match {
            case Nil => closed
            case parent :: aboveParent =>
              parent.add(closed)
              closing(parent, aboveParent)
          }
      }
    }
    closing(outermost, Nil)
  }
}
