package compatibility.fixture

import org.scalatest.DoNotDiscover
import org.scalatest.Suites
import org.scalatest.funsuite.AnyFunSuite

class NestingSpec extends Suites(new FirstNestedSpec, new SecondNestedSpec)

@DoNotDiscover
class FirstNestedSpec extends AnyFunSuite {
  test("succeeds in the first nested suite") {
    assert(List(1, 2).sum == 3)
  }
}

@DoNotDiscover
class SecondNestedSpec extends AnyFunSuite {
  test("fails in the second nested suite") {
    assert(List(1, 2).sum == 4)
  }
}
