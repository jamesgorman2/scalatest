package samples

import org.scalatest.funsuite.AnyFunSuite

final class CalculatorSuite extends AnyFunSuite {
  test("adds two numbers") {
    println("dividing now")
    assert(1 + 1 == 2)
  }
}
