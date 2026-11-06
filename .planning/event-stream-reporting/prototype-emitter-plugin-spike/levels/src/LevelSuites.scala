// PROTOTYPE - throwaway. Shared by quiet.test (testLogLevel Error) and loud.test (default).
package levels

import org.scalatest.funsuite.AnyFunSuite

class LevelOk extends AnyFunSuite {
  test("ok1") { System.out.println("MARK-OUT LevelOk ok1") }
  test("ok2") {}
}
class LevelFail extends AnyFunSuite {
  test("passes") {}
  test("fails") { assert(1 + 1 == 3) }
}
