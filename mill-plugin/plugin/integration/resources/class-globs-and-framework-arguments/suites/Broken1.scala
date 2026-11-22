package samples

import org.scalatest.BeforeAndAfterAll

final class Broken1 extends MarkingSuite with BeforeAndAfterAll {
  override def beforeAll(): Unit = throw new IllegalStateException("beforeAll failed")

  test("fast", Fast) {}
}
