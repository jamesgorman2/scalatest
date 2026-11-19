package org.scalatest.consolidation

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

final class WorkerSpec extends AnyWordSpec with should.Matchers {

  "A fork group" when {
    "made from position 0" should {
      "hold position 0" in {
        ForkGroup.from(0).map(_.position) shouldBe Right(0)
      }
    }
    "made from a negative position" should {
      "be refused with the position" in {
        ForkGroup.from(-1) shouldBe Left("a fork group's position must not be negative, but was -1")
      }
    }
  }

  "A worker index" when {
    "made from 0" should {
      "hold 0" in {
        WorkerIndex.from(0).map(_.value) shouldBe Right(0)
      }
    }
    "made from a negative value" should {
      "be refused with the value" in {
        WorkerIndex.from(-1) shouldBe Left("a worker index must not be negative, but was -1")
      }
    }
  }
}
