// PROTOTYPE - throwaway. Three passing suites of twelve 500 ms tests, shared by modules a to e.
package fx

import org.scalatest.funsuite.AnyFunSuite

class S1 extends AnyFunSuite { for (i <- 1 to 12) test(s"t$i") { Thread.sleep(500) } }
class S2 extends AnyFunSuite { for (i <- 1 to 12) test(s"t$i") { Thread.sleep(500) } }
class S3 extends AnyFunSuite { for (i <- 1 to 12) test(s"t$i") { Thread.sleep(500) } }
