package org.scalatest.consolidation

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

final class WorkerSpec extends AnyWordSpec with should.Matchers {

  "A fork group" when {
    "made at position 0" should {
      "hold position 0" in {
        ForkGroup.at(0).position shouldBe 0
      }
    }
    "made at a negative position" should {
      "throw an IllegalArgumentException naming the position" in {
        the[IllegalArgumentException] thrownBy ForkGroup.at(-1) should have message "a fork group's position must not be negative, but was -1"
      }
    }
  }

  "A worker index" when {
    "made from 0" should {
      "hold 0" in {
        WorkerIndex(0).value shouldBe 0
      }
    }
    "made from a negative value" should {
      "throw an IllegalArgumentException naming the value" in {
        the[IllegalArgumentException] thrownBy WorkerIndex(-1) should have message "a worker index must not be negative, but was -1"
      }
    }
  }
}
