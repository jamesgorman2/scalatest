package samples

import org.scalatest.funsuite.AnyFunSuite

final class RowSuite extends AnyFunSuite {
  test("talks") {
    info("one")
    note("two")
    alert("three")
    markup("four")
  }
}
