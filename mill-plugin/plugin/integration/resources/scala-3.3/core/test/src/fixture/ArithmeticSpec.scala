package fixture

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should

final class ArithmeticSpec extends AnyFreeSpec with should.Matchers {
  "Addition" - {
    "adds two numbers" in {
      (1 + 1) shouldBe 2
    }
  }
}
