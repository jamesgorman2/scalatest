package org.scalatest.events

import scala.annotation.tailrec

private[events] abstract class OpenThrowable[Source, Linked](cause: Option[Source], suppressed: Iterator[Source]) {

  private var unreadCause = cause
  private var unreadSuppressed = suppressed
  private var addsCause = false
  private var causeLinked: Option[Linked] = None
  private var suppressedLinked = Vector.empty[Linked]

  def closed: Linked

  protected def linkedCause: Option[Linked] = causeLinked

  protected def linkedSuppressed: Vector[Linked] = suppressedLinked

  private def nextLink(): Option[Source] = {
    // The sources are dropped once all are read: until its links are first read, the closed value can keep this open
    // throwable reachable, and the sources must not stay reachable that long.
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

  def closedFrom[Source, Linked](outermost: OpenThrowable[Source, Linked])(
    linkedOrOpened: Source => Either[Linked, OpenThrowable[Source, Linked]]
  ): Linked = {
    // The throwables whose links are still being added are kept in `outer`, not on the call stack, so that a long chain
    // of causes cannot overflow the stack.
    @tailrec
    def closing(innermost: OpenThrowable[Source, Linked], outer: List[OpenThrowable[Source, Linked]]): Linked = {
      innermost.nextLink() match {
        case Some(source) =>
          linkedOrOpened(source) match {
            case Left(linked) => {
              innermost.add(linked)
              closing(innermost, outer)
            }
            case Right(opened) => closing(opened, innermost :: outer)
          }
        case None => {
          val closed = innermost.closed
          outer match {
            case Nil => closed
            case parent :: aboveParent => {
              parent.add(closed)
              closing(parent, aboveParent)
            }
          }
        }
      }
    }
    closing(outermost, Nil)
  }
}
