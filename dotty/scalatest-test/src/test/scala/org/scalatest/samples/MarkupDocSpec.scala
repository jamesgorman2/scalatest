package org.scalatest.samples

import org.scalatest.DoNotDiscover
import org.scalatest.DocSpec

@DoNotDiscover
class MarkupDocSpec extends DocSpec {

  val doc: IndexedSeq[Snippet] = Vector(MarkupSnippet("A stack pops"))
}
