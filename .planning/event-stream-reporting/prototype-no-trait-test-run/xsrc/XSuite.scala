// PROTOTYPE - throwaway. Shared by the Scala.js and Native fixtures: numbered lines on stdout and
// stderr, so a check can tell whether they reached the console or the plugin, and in what order.
package x

import org.scalatest.funsuite.AnyFunSuite

class XSuite extends AnyFunSuite {
  for (i <- 1 to 2) test(s"x$i") {
    for (j <- 1 to 20) {
      System.out.println(s"X-OUT t$i n=$j")
      System.err.println(s"X-ERR t$i n=$j")
    }
  }
}

class YSuite extends AnyFunSuite {
  test("y") {
    System.out.println("Y-OUT only")
    System.err.println("Y-ERR only")
  }
}
