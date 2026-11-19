package samples

import org.scalatest.funsuite.AnyFunSuite

final class RowSuite extends AnyFunSuite {
  test("talks") {
    val away = new Thread(() => info("away"))
    away.start()
    away.join()
  }
}
