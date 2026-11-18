package samples

import org.scalatest.funsuite.AnyFunSuite

final class FrameworkSuite extends AnyFunSuite {
  test("runs") {
    assert(1 + 1 == 2)
  }
}
