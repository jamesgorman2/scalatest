package org.scalatest.mill.tags

import org.scalatest.Tag

object CoreTag extends Tag(classOf[Core].getName) {
  def when(inCoreSet: Boolean): Seq[Tag] = if (inCoreSet) Seq(this) else Nil
}
