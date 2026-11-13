package compatibility.fixture

import org.scalatest.FutureOutcome
import org.scalatest.Outcome
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.funspec.AsyncFunSpec
import org.scalatest.funspec.FixtureAnyFunSpec
import org.scalatest.funspec.FixtureAsyncFunSpec
import org.scalatest.funspec.PathAnyFunSpec

import scala.concurrent.Future

class AnyFunSpecSpec extends AnyFunSpec {
  describe("A scope") {
    info("info from a scope")
    note("note from a scope")
    alert("alert from a scope")
    markup("markup from a scope")

    it("succeeds") {
      assert(List(1, 2).sum == 3)
    }
    it("fails") {
      assert(List(1, 2).sum == 4)
    }
    ignore("is ignored") {
      assert(List(1, 2).sum == 3)
    }
    it("is pending") {
      pending
    }
    it("is cancelled") {
      cancel("cancelled by the test")
    }
    it("sends messages") {
      info("info from a test")
      note("note from a test")
      alert("alert from a test")
      markup("markup from a test")
    }
    describe("holding a nested scope") {
      it("succeeds") {
        assert(List(1, 2).sum == 3)
      }
    }
  }
  describe("A pending scope") {
    pending
  }
}

class AsyncFunSpecSpec extends AsyncFunSpec {
  describe("A scope") {
    it("succeeds") {
      Future.successful(assert(List(1, 2).sum == 3))
    }
    it("fails with an exception") {
      Future.failed(new IllegalArgumentException("thrown in a future"))
    }
  }
}

class FixtureAnyFunSpecSpec extends FixtureAnyFunSpec {
  type FixtureParam = String

  def withFixture(test: OneArgTest): Outcome = test("fixture")

  describe("A scope") {
    it("succeeds") { fixture =>
      assert(fixture == "fixture")
    }
    it("is pending") { fixture =>
      pending
    }
  }
}

class FixtureAsyncFunSpecSpec extends FixtureAsyncFunSpec {
  type FixtureParam = String

  def withFixture(test: OneArgAsyncTest): FutureOutcome = test("fixture")

  describe("A scope") {
    it("succeeds") { fixture =>
      Future.successful(assert(fixture == "fixture"))
    }
    it("fails") { fixture =>
      Future(assert(fixture == "another fixture"))
    }
  }
}

class PathAnyFunSpecSpec extends PathAnyFunSpec {
  describe("A scope") {
    it("sends messages") {
      info("info from a path test")
      note("note from a path test")
      alert("alert from a path test")
      markup("markup from a path test")
    }
    it("fails") {
      assert(List(1, 2).sum == 4)
    }
    describe("holding a nested scope") {
      it("succeeds") {
        assert(List(1, 2).sum == 3)
      }
    }
  }
}
