package org.scalatest.consolidation

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

final class TestClassNameSpec extends AnyWordSpec with should.Matchers {

  "A test class name" when {
    "made from a test class" should {
      "be the class's binary name, which ScalaTest reports" in {
        TestClassName.of(classOf[samples.UnicodeSuite]) shouldBe TestClassName("samples.UnicodeSuite")
      }
      "separate a nested class from its enclosing class with $" in {
        TestClassName.of(classOf[TestClassNameSpec.NestedSuite]) shouldBe
          TestClassName("org.scalatest.consolidation.TestClassNameSpec$NestedSuite")
      }
    }
  }
}

object TestClassNameSpec {

  final class NestedSuite
}
