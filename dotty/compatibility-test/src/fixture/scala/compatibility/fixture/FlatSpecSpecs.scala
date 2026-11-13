package compatibility.fixture

import org.scalatest.FutureOutcome
import org.scalatest.Outcome
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.flatspec.FixtureAnyFlatSpec
import org.scalatest.flatspec.FixtureAsyncFlatSpec

import scala.concurrent.Future

class AnyFlatSpecSpec extends AnyFlatSpec {
  "A subject" should "succeed" in {
    assert(List(1, 2).sum == 3)
  }
  it must "fail" in {
    assert(List(1, 2).sum == 4)
  }
  it should "be ignored" ignore {
    assert(List(1, 2).sum == 3)
  }

  behavior of "Another subject"

  it can "be pending" in {
    pending
  }
  it should "be cancelled" in {
    cancel("cancelled by the test")
  }
}

class AsyncFlatSpecSpec extends AsyncFlatSpec {
  "A subject" should "succeed" in {
    Future.successful(assert(List(1, 2).sum == 3))
  }
  it must "fail" in {
    Future(assert(List(1, 2).sum == 4))
  }
}

class FixtureAnyFlatSpecSpec extends FixtureAnyFlatSpec {
  type FixtureParam = String

  def withFixture(test: OneArgTest): Outcome = test("fixture")

  "A subject" should "succeed" in { fixture =>
    assert(fixture == "fixture")
  }

  behavior of "Another subject"

  it can "be cancelled" in { fixture =>
    assume(fixture == "another fixture")
  }
}

class FixtureAsyncFlatSpecSpec extends FixtureAsyncFlatSpec {
  type FixtureParam = String

  def withFixture(test: OneArgAsyncTest): FutureOutcome = test("fixture")

  "A subject" should "succeed" in { fixture =>
    Future.successful(assert(fixture == "fixture"))
  }
  it should "be ignored" ignore { fixture =>
    Future.successful(assert(fixture == "fixture"))
  }
}
