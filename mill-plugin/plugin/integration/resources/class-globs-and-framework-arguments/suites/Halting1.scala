package samples

import org.scalatest.BeforeAndAfterAll

final class Halting1 extends MarkingSuite with BeforeAndAfterAll {
  override def beforeAll(): Unit = Runtime.getRuntime.halt(1)

  test("fast", Fast) {}
}
