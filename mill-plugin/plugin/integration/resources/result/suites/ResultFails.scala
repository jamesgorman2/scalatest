package samples

import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

final class ResultSuite extends AnyFunSuite with BeforeAndAfterAll {
  test("fails") {
    assert(1 + 1 == 3)
  }

  test("succeeds") {
    assert(1 + 1 == 2)
  }
}
