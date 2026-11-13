package compatibility.fixture

import org.scalatest.FutureOutcome
import org.scalatest.Outcome
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.freespec.FixtureAnyFreeSpec
import org.scalatest.freespec.FixtureAsyncFreeSpec
import org.scalatest.freespec.PathAnyFreeSpec

import scala.concurrent.Future

class AnyFreeSpecSpec extends AnyFreeSpec {
  "A scope" - {
    "succeeds" in {
      assert(List(1, 2).sum == 3)
    }
    "fails" in {
      assert(List(1, 2).sum == 4)
    }
    "is ignored" ignore {
      assert(List(1, 2).sum == 3)
    }
    "is pending" in {
      pending
    }
    "holding a nested scope" - {
      "is cancelled" in {
        cancel("cancelled by the test")
      }
    }
  }
  "A pending scope" - {
    pending
  }
}

class AsyncFreeSpecSpec extends AsyncFreeSpec {
  "A scope" - {
    "succeeds" in {
      Future.successful(assert(List(1, 2).sum == 3))
    }
    "is cancelled" in {
      Future(cancel("cancelled in a future"))
    }
  }
}

class FixtureAnyFreeSpecSpec extends FixtureAnyFreeSpec {
  type FixtureParam = String

  def withFixture(test: OneArgTest): Outcome = test("fixture")

  "A scope" - {
    "succeeds" in { fixture =>
      assert(fixture == "fixture")
    }
    "fails" in { fixture =>
      assert(fixture == "another fixture")
    }
  }
}

class FixtureAsyncFreeSpecSpec extends FixtureAsyncFreeSpec {
  type FixtureParam = String

  def withFixture(test: OneArgAsyncTest): FutureOutcome = test("fixture")

  "A scope" - {
    "succeeds" in { fixture =>
      Future.successful(assert(fixture == "fixture"))
    }
    "is ignored" ignore { fixture =>
      Future.successful(assert(fixture == "fixture"))
    }
  }
}

class PathAnyFreeSpecSpec extends PathAnyFreeSpec {
  "A scope" - {
    "sends messages" in {
      info("info from a path test")
      note("note from a path test")
      alert("alert from a path test")
      markup("markup from a path test")
    }
    "is pending" in {
      pending
    }
    "holding a nested scope" - {
      "succeeds" in {
        assert(List(1, 2).sum == 3)
      }
    }
  }
}
