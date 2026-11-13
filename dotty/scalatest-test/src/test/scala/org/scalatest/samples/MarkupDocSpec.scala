package org.scalatest.samples

import org.scalatest.DoNotDiscover
import org.scalatest.DocSpec

// In ScalaTest's package because DocSpec is private to it.
@DoNotDiscover
class MarkupDocSpec extends DocSpec {

  val doc: IndexedSeq[Snippet] = Vector(MarkupSnippet("A stack pops"))
}
