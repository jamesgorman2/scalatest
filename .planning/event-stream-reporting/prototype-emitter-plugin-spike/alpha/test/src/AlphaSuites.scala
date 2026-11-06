// PROTOTYPE - throwaway. Four passing suites, run in two fork groups with testParallelism on.
package alpha

import org.scalatest.funsuite.AnyFunSuite

abstract class Slow(n: Int) extends AnyFunSuite {
  for (i <- 1 to n) test(s"t$i") {
    System.out.println(s"MARK-OUT ${getClass.getSimpleName} t$i pid=${ProcessHandle.current.pid}")
    System.err.println(s"MARK-ERR ${getClass.getSimpleName} t$i")
    Thread.sleep(400)
  }
}
class A1 extends Slow(4)
class A2 extends Slow(4)
class A3 extends Slow(4)
class A4 extends Slow(4)
