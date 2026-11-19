package samples

import org.scalatest.funsuite.AnyFunSuite

final class ZSuite extends AnyFunSuite {
  override def suiteName: String = "AlphaSuite"

  test("adds") {
    assert(1 + 1 == 2)
  }
}
