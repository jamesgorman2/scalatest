// PROTOTYPE - throwaway. Run with the shim testFramework; writes markers to both streams.
package shimmed

import org.scalatest.funsuite.AnyFunSuite

class ShimOk extends AnyFunSuite {
  test("ok") {
    System.out.println("MARK-OUT ShimOk ok")
    System.err.println("MARK-ERR ShimOk ok")
    Console.out.println("MARK-CONSOLE-OUT ShimOk ok")
  }
  test("fails") { assert(1 + 1 == 3) }
}
