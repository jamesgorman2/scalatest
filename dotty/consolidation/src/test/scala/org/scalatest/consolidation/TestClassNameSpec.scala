package org.scalatest.consolidation

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

final class TestClassNameSpec extends AnyWordSpec with should.Matchers {

  "A test class name" when {
    "made of a test class" should {
      "be the class's fully qualified name" in {
        TestClassName.of(classOf[samples.UnicodeSuite]) shouldBe TestClassName("samples.UnicodeSuite")
      }
    }
  }
}
