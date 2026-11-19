package samples

import org.scalatest.funsuite.AnyFunSuite

final class RowSuite extends AnyFunSuite {
  test("loops") {
    val a = new RuntimeException("a")
    val b = new RuntimeException("b", a)
    a.initCause(b)
    throw b
  }
}
