package samples

import org.scalatest.Tag
import org.scalatest.funsuite.AnyFunSuite

object Slow extends Tag("samples.Slow")

final class SpeedSuite extends AnyFunSuite {
  test("fast") {
    assert(true)
  }

  test("slow", Slow) {
    println("slow ran")
    assert(true)
  }
}
