package samples

import org.scalatest.funsuite.AnyFunSuite

final class ChattySuite extends AnyFunSuite {
  test("chats") {
    for (i <- 1 to 2000) {
      System.out.println(s"out $i")
      System.err.println(s"err $i")
    }
  }
}
