package compatibility.fixture

import org.scalatest.FutureOutcome
import org.scalatest.Outcome
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.featurespec.AsyncFeatureSpec
import org.scalatest.featurespec.FixtureAnyFeatureSpec
import org.scalatest.featurespec.FixtureAsyncFeatureSpec

import scala.concurrent.Future

class AnyFeatureSpecSpec extends AnyFeatureSpec {
  Feature("A feature") {
    Scenario("succeeds") {
      assert(List(1, 2).sum == 3)
    }
    Scenario("fails") {
      assert(List(1, 2).sum == 4)
    }
    ignore("is ignored") {
      assert(List(1, 2).sum == 3)
    }
    Scenario("is pending") {
      pending
    }
    Scenario("is cancelled") {
      cancel("cancelled by the test")
    }
  }
}

class AsyncFeatureSpecSpec extends AsyncFeatureSpec {
  Feature("A feature") {
    Scenario("succeeds") {
      Future.successful(assert(List(1, 2).sum == 3))
    }
    Scenario("fails") {
      Future(assert(List(1, 2).sum == 4))
    }
    ignore("is ignored") {
      Future.successful(assert(List(1, 2).sum == 3))
    }
  }
}

class FixtureAnyFeatureSpecSpec extends FixtureAnyFeatureSpec {
  type FixtureParam = String

  def withFixture(test: OneArgTest): Outcome = test("fixture")

  Feature("A feature") {
    Scenario("succeeds") { fixture =>
      assert(fixture == "fixture")
    }
    Scenario("fails without the fixture") { () =>
      assert(List(1, 2).sum == 4)
    }
    ignore("is ignored") { fixture =>
      assert(fixture == "fixture")
    }
    ignore("is ignored without the fixture") { () =>
      assert(List(1, 2).sum == 3)
    }
  }
}

class FixtureAsyncFeatureSpecSpec extends FixtureAsyncFeatureSpec {
  type FixtureParam = String

  def withFixture(test: OneArgAsyncTest): FutureOutcome = test("fixture")

  Feature("A feature") {
    Scenario("succeeds") { fixture =>
      Future.successful(assert(fixture == "fixture"))
    }
    Scenario("fails") { fixture =>
      Future(assert(fixture == "another fixture"))
    }
    ignore("is ignored") { fixture =>
      Future.successful(assert(fixture == "fixture"))
    }
  }
}
