package samples

import org.scalatest.funsuite.AnyFunSuite

final class RowSuite extends AnyFunSuite {
  test("throws") {
    val e = new RuntimeException("outer", new java.io.IOException("inner"))
    e.addSuppressed(new IllegalStateException("closing"))
    throw e
  }
}
