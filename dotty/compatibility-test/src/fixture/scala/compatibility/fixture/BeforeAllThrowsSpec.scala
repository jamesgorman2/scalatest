package compatibility.fixture

import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

class BeforeAllThrowsSpec extends AnyFunSuite with BeforeAndAfterAll {
  override def beforeAll(): Unit = {
    throw new IllegalStateException("beforeAll failed")
  }

  test("never runs") {
    assert(List(1, 2).sum == 3)
  }
}
