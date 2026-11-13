package org.scalatest.compatibility

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class ComparedSpec extends AnyWordSpec with Matchers {

  "Compared lines" when {
    "the same" should {
      "have no difference" in {
        Compared.firstDifference(Compared(List("a", "b"), List("a", "b"))) shouldBe None
      }
    }
    "different in a line" should {
      "give the first line that differs" in {
        Compared.firstDifference(Compared(List("a", "b", "c"), List("a", "x", "y"))) shouldBe Some("line 2: 3.2.20 wrote [b], the fork wrote [x]")
      }
    }
    "different in length" should {
      "give the first line that only one side has" in {
        Compared.firstDifference(Compared(List("a"), List("a", "b"))) shouldBe Some("line 2: 3.2.20 wrote no line, the fork wrote [b]")
      }
    }
  }
}
