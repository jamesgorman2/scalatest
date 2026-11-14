package samples

import org.scalatest.funsuite.AnyFunSuite

final class BrokenCtorSpec extends AnyFunSuite {
  BrokenCtorSpec.loadConfig()

  test("never registered") {}
}

object BrokenCtorSpec {
  def loadConfig(): Unit = throw new IllegalStateException("no config")
}
