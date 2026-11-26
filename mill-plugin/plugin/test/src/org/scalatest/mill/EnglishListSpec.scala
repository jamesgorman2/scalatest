package org.scalatest.mill

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

final class EnglishListSpec extends AnyWordSpec with should.Matchers {

  "An English list" when {
    "of several items" should {
      "separate them with commas, and the last two with and" in {
        EnglishList.of(Seq("a", "b", "c")) shouldBe "a, b and c"
      }
    }

    "of two items" should {
      "join them with and" in {
        EnglishList.of(Seq("a", "b")) shouldBe "a and b"
      }
    }

    "of one item" should {
      "be that item" in {
        EnglishList.of(Seq("a")) shouldBe "a"
      }
    }

    "of no items" should {
      "be empty" in {
        EnglishList.of(Nil) shouldBe ""
      }
    }
  }
}
