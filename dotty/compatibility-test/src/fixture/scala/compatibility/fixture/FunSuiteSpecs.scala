package compatibility.fixture

import org.scalatest.FutureOutcome
import org.scalatest.Outcome
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.funsuite.FixtureAnyFunSuite
import org.scalatest.funsuite.FixtureAsyncFunSuite

import scala.concurrent.Future

class AnyFunSuiteSpec extends AnyFunSuite {
  info("info from a suite")
  note("note from a suite")
  alert("alert from a suite")
  markup("markup from a suite")

  test("succeeds") {
    assert(List(1, 2).sum == 3)
  }
  test("fails") {
    assert(List(1, 2).sum == 4)
  }
  ignore("is ignored") {
    assert(List(1, 2).sum == 3)
  }
  test("is pending") {
    pending
  }
  test("is cancelled") {
    cancel("cancelled by the test")
  }
  test("writes to stdout and to stderr") {
    println("a line on stdout")
    Console.err.println("a line on stderr")
  }
  test("throws an exception that has a cause") {
    throw new IllegalStateException("outer", new java.io.IOException("inner"))
  }
}

class AsyncFunSuiteSpec extends AsyncFunSuite {
  test("succeeds") {
    Future.successful(assert(List(1, 2).sum == 3))
  }
  test("fails") {
    Future(assert(List(1, 2).sum == 4))
  }
}

class FixtureAnyFunSuiteSpec extends FixtureAnyFunSuite {
  type FixtureParam = String

  def withFixture(test: OneArgTest): Outcome = test("fixture")

  test("succeeds") { fixture =>
    assert(fixture == "fixture")
  }
  test("is cancelled") { fixture =>
    assume(fixture == "another fixture")
  }
}

class FixtureAsyncFunSuiteSpec extends FixtureAsyncFunSuite {
  type FixtureParam = String

  def withFixture(test: OneArgAsyncTest): FutureOutcome = test("fixture")

  test("succeeds") { fixture =>
    Future.successful(assert(fixture == "fixture"))
  }
  ignore("is ignored") { fixture =>
    Future.successful(assert(fixture == "fixture"))
  }
}
