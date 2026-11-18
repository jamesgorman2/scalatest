package samples

import org.scalatest.propspec.AnyPropSpec

final class SqrtPropSpec extends AnyPropSpec {
  property("sqrt of a square is the root") {
    assert(math.sqrt(9.0) == 3.0)
  }
}
