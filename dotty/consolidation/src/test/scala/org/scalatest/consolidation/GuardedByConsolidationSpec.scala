package org.scalatest.consolidation

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

final class GuardedByConsolidationSpec extends AnyWordSpec with should.Matchers {

  private def guarded(): (Consolidation, GuardedByConsolidation[Int]) = {
    val consolidation = Consolidation.start(_ => (), coloured = false)
    (consolidation, new GuardedByConsolidation(consolidation, 1))
  }

  "A value guarded by a consolidation" when {
    "read without the consolidation's lock" should {
      "fail" in {
        val (_, value) = guarded()
        (the[AssertionError] thrownBy value.get).getMessage should include ("the consolidation's lock is not held")
      }
    }
    "set without the consolidation's lock" should {
      "fail" in {
        val (_, value) = guarded()
        (the[AssertionError] thrownBy value.set(2)).getMessage should include ("the consolidation's lock is not held")
      }
    }
    "set and read under the consolidation's lock" should {
      "hold the value set" in {
        val (consolidation, value) = guarded()
        consolidation.synchronized {
          value.set(2)
          value.get
        } shouldBe 2
      }
    }
  }
}
