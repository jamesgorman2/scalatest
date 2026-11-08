// PROTOTYPE - throwaway. A suite with one failing test among passing ones.
package fx

import org.scalatest.funsuite.AnyFunSuite

class FailingSuite extends AnyFunSuite {
  for (i <- 1 to 4) test(s"ok$i") { Thread.sleep(500) }
  test("fails") { Thread.sleep(500); assert(1 + 1 == 3) }
  for (i <- 5 to 8) test(s"ok$i") { Thread.sleep(500) }
}
