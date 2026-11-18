package samples

import org.scalatest.funsuite.AnyFunSuite

final class FailSuite extends AnyFunSuite {
  test("subtracts") {
    assert(3 - 1 == 1)
  }
}
