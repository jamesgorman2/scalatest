package samples

import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite

@DoNotDiscover
final class InnerSuite extends AnyFunSuite {
  test("adds") {
    assert(1 + 1 == 2)
  }
}
