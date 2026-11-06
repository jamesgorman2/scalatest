// PROTOTYPE - throwaway. One passing suite and one with a failing test.
package beta

import org.scalatest.funsuite.AnyFunSuite

class BetaOk extends AnyFunSuite {
  for (i <- 1 to 3) test(s"ok$i") {
    System.out.println(s"MARK-OUT BetaOk ok$i")
    System.err.println(s"MARK-ERR BetaOk ok$i")
    Thread.sleep(300)
  }
}
class BetaFail extends AnyFunSuite {
  test("passes") { System.out.println("MARK-OUT BetaFail passes"); Thread.sleep(300) }
  test("fails") { System.out.println("MARK-OUT BetaFail fails"); Thread.sleep(300); assert(1 + 1 == 3) }
  test("passes after") { Thread.sleep(300) }
}
