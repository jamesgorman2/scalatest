package samples.discovered

import org.scalatest.funsuite.AnyFunSuite

// Not @DoNotDiscover: a test copies this package's class files to a runpath of its own for discovery to find.
class OneTestSpec extends AnyFunSuite {

  test("succeeds") {
    succeed
  }
}
