package samples

import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

final class ResultSuite extends AnyFunSuite with BeforeAndAfterAll {
  ignore("ignored") {
    assert(1 + 1 == 2)
  }

  test("pending") {
    pending
  }

  test("succeeds") {
    assert(1 + 1 == 2)
  }
}
