// PROTOTYPE - throwaway. Fails to compile, so this module's tests cannot start.
package broken

import org.scalatest.funsuite.AnyFunSuite

class BrokenSuite extends AnyFunSuite {
  test("x") { val n: Int = "not an int" }
}
