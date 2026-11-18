package samples

import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

final class ResultSuite extends AnyFunSuite with BeforeAndAfterAll {
  override def beforeAll(): Unit =
    throw new IllegalStateException("no fixture")

  test("succeeds") {
    assert(1 + 1 == 2)
  }
}
