package samples

import org.scalatest.flatspec.AnyFlatSpec

final class StackFlatSpec extends AnyFlatSpec {
  "A Stack" must "have size 0" in {
    assert(List(1).size == 0)
  }
}
