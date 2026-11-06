// PROTOTYPE - throwaway. Twelve one-second tests per suite, for the interactive console cases.
package slow

import org.scalatest.funsuite.AnyFunSuite

abstract class OneSecond extends AnyFunSuite {
  for (i <- 1 to 12) test(s"t$i") {
    System.out.println(s"MARK-OUT ${getClass.getSimpleName} t$i")
    Thread.sleep(1000)
  }
}
class S1 extends OneSecond
class S2 extends OneSecond
