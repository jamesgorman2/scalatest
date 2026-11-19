package samples

import org.scalatest.funsuite.AnyFunSuite

final class EscapeSuite extends AnyFunSuite {
  test("escapes") {
    info("tab\t quote\" slash\\ bell\u0007 é 😀 lone\uD800 end")
  }
}
