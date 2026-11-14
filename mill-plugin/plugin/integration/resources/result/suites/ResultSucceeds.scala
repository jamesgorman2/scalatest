package samples

import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

final class ResultSuite extends AnyFunSuite with BeforeAndAfterAll {
  test("succeeds") {
    assert(1 + 1 == 2)
  }
}
