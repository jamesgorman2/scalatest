package samples

import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.propspec.AnyPropSpec

@DoNotDiscover
class SqrtPropSpec extends AnyPropSpec {

  property("sqrt of a square is the root") {
    assert(math.sqrt(49.0) == 7.0)
  }
}

@DoNotDiscover
class AbortSuite extends AnyFunSuite {

  test("throws on pop") {
    throw new OutOfMemoryError("Java heap space")
  }
}
