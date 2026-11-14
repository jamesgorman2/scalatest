package samples

import org.scalatest.flatspec.AnyFlatSpec

final class QueueFlatSpec extends AnyFlatSpec {
  "A Queue" should "be empty" in {}

  it must "have size 0" in {
    System.out.println("dividing now")
    System.err.println("warn: careful")
    fail()
  }
}
