package compatibility.fixture

import org.scalatest.Outcome
import org.scalatest.propspec.AnyPropSpec
import org.scalatest.propspec.FixtureAnyPropSpec

class AnyPropSpecSpec extends AnyPropSpec {
  property("succeeds") {
    assert(List(1, 2, 3).forall(_ > 0))
  }
  property("fails") {
    assert(List(1, 2, 3).forall(_ > 1))
  }
  ignore("is ignored") {
    assert(List(1, 2, 3).forall(_ > 0))
  }
}

class FixtureAnyPropSpecSpec extends FixtureAnyPropSpec {
  type FixtureParam = List[Int]

  def withFixture(test: OneArgTest): Outcome = test(List(1, 2, 3))

  property("succeeds") { numbers =>
    assert(numbers.forall(_ > 0))
  }
  property("fails") { numbers =>
    assert(numbers.forall(_ > 1))
  }
}
