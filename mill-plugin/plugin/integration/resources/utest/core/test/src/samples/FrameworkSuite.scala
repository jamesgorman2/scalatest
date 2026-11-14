package samples

import utest.*

object FrameworkSuite extends TestSuite {
  val tests: Tests = Tests {
    test("runs") {
      assert(1 + 1 == 2)
    }
  }
}
