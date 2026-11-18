package samples

import org.scalatest.funsuite.AnyFunSuite

final class ExitSuite extends AnyFunSuite {
  test("exits") {
    System.exit(0)
  }
}
