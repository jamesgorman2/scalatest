package samples.discovered

import org.scalatest.funsuite.AnyFunSuite

// Discoverable, as the other samples of this package are: a test copies its class file to a runpath of its own for discovery to find.
class OneTestSpec extends AnyFunSuite {

  test("succeeds") {
    succeed
  }
}
