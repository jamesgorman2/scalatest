package compatibility.fixture

import org.scalatest.FutureOutcome
import org.scalatest.Outcome
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.wordspec.AsyncWordSpec
import org.scalatest.wordspec.FixtureAnyWordSpec
import org.scalatest.wordspec.FixtureAsyncWordSpec

import scala.concurrent.Future

class AnyWordSpecSpec extends AnyWordSpec {
  "A subject" when {
    "in a state" should {
      "succeed" in {
        assert(List(1, 2).sum == 3)
      }
      "fail" in {
        assert(List(1, 2).sum == 4)
      }
      "be ignored" ignore {
        assert(List(1, 2).sum == 3)
      }
    }
  }
  "Another subject" can {
    "be pending" in {
      pending
    }
    "be cancelled" in {
      cancel("cancelled by the test")
    }
  }
}

class AsyncWordSpecSpec extends AsyncWordSpec {
  "A subject" when {
    "in a state" should {
      "succeed" in {
        Future.successful(assert(List(1, 2).sum == 3))
      }
    }
  }
  "Another subject" can {
    "fail" in {
      Future(assert(List(1, 2).sum == 4))
    }
  }
}

class FixtureAnyWordSpecSpec extends FixtureAnyWordSpec {
  type FixtureParam = String

  def withFixture(test: OneArgTest): Outcome = test("fixture")

  "A subject" when {
    "in a state" should {
      "succeed" in { fixture =>
        assert(fixture == "fixture")
      }
    }
  }
  "Another subject" can {
    "be cancelled" in { fixture =>
      assume(fixture == "another fixture", "the fixture is not the one assumed")
    }
  }
}

class FixtureAsyncWordSpecSpec extends FixtureAsyncWordSpec {
  type FixtureParam = String

  def withFixture(test: OneArgAsyncTest): FutureOutcome = test("fixture")

  "A subject" when {
    "in a state" should {
      "succeed" in { fixture =>
        Future.successful(assert(fixture == "fixture"))
      }
    }
  }
  "Another subject" can {
    "be pending" in { fixture =>
      pending
    }
  }
}
