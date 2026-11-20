package org.scalatest.events

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class NodeIdSpec extends AnyWordSpec with Matchers {

  private val stackSuite = NodeId.topLevelSuite(SuiteId("samples.StackSuite"), 1)

  "A top-level suite's node ID" should {
    "be its suite ID and index alone" in {
      stackSuite shouldBe NodeId(Vector(NodePlace.Suite(SuiteId("samples.StackSuite"), 1)))
    }
  }

  "A nested suite's node ID" should {
    "be its parent's, then its suite ID and index" in {
      NodeId.nestedSuite(stackSuite, SuiteId("samples.HelperSuite"), 2) shouldBe
        NodeId(stackSuite.places :+ NodePlace.Suite(SuiteId("samples.HelperSuite"), 2))
    }
  }

  "A scope's node ID" should {
    "be its parent's, then its registered text and index" in {
      NodeId.scope(stackSuite, "A Stack when empty", 0) shouldBe NodeId(stackSuite.places :+ NodePlace.Scope("A Stack when empty", 0))
    }
  }

  "A test's node ID" should {
    "be its suite's, then its test name, whichever scope it sits in" in {
      NodeId.test(stackSuite, TestName("A Stack pops")) shouldBe NodeId(stackSuite.places :+ NodePlace.Test(TestName("A Stack pops")))
    }
  }
}
