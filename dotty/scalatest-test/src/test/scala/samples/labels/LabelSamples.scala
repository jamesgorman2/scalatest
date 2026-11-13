package samples.labels

import org.scalatest.DoNotDiscover
import org.scalatest.FutureOutcome
import org.scalatest.Outcome
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.featurespec.AsyncFeatureSpec
import org.scalatest.featurespec.FixtureAnyFeatureSpec
import org.scalatest.featurespec.FixtureAsyncFeatureSpec
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.flatspec.FixtureAnyFlatSpec
import org.scalatest.flatspec.FixtureAsyncFlatSpec
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.freespec.FixtureAnyFreeSpec
import org.scalatest.freespec.FixtureAsyncFreeSpec
import org.scalatest.freespec.PathAnyFreeSpec
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.funspec.AsyncFunSpec
import org.scalatest.funspec.FixtureAnyFunSpec
import org.scalatest.funspec.FixtureAsyncFunSpec
import org.scalatest.funspec.PathAnyFunSpec
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.funsuite.FixtureAnyFunSuite
import org.scalatest.funsuite.FixtureAsyncFunSuite
import org.scalatest.propspec.AnyPropSpec
import org.scalatest.propspec.FixtureAnyPropSpec
import org.scalatest.refspec.RefSpec
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.wordspec.AsyncWordSpec
import org.scalatest.wordspec.FixtureAnyWordSpec
import org.scalatest.wordspec.FixtureAsyncWordSpec

// One suite of each style for each declaration, named <style>With<declaration>. No sample's test body matters:
// only what each registers.

trait StringFixture { this: org.scalatest.FixtureTestSuite =>
  type FixtureParam = String
  def withFixture(test: OneArgTest): Outcome = test("fixture")
}

trait AsyncStringFixture { this: org.scalatest.FixtureAsyncTestSuite =>
  type FixtureParam = String
  def withFixture(test: OneArgAsyncTest): FutureOutcome = test("fixture")
}

@DoNotDiscover
class AnyFunSuiteWithTest extends AnyFunSuite {
  test("t") { succeed }
}

@DoNotDiscover
class AsyncFunSuiteWithTest extends AsyncFunSuite {
  test("t") { succeed }
}

@DoNotDiscover
class FixtureAnyFunSuiteWithTest extends FixtureAnyFunSuite with StringFixture {
  test("t") { _ => succeed }
}

@DoNotDiscover
class FixtureAsyncFunSuiteWithTest extends FixtureAsyncFunSuite with AsyncStringFixture {
  test("t") { _ => succeed }
}

@DoNotDiscover
class AnyPropSpecWithProperty extends AnyPropSpec {
  property("t") { succeed }
}

@DoNotDiscover
class FixtureAnyPropSpecWithProperty extends FixtureAnyPropSpec with StringFixture {
  property("t") { _ => succeed }
}

@DoNotDiscover
class AnyFunSpecWithDescribe extends AnyFunSpec {
  describe("s") {
    it("t") { succeed }
  }
}

@DoNotDiscover
class AsyncFunSpecWithDescribe extends AsyncFunSpec {
  describe("s") {
    it("t") { succeed }
  }
}

@DoNotDiscover
class FixtureAnyFunSpecWithDescribe extends FixtureAnyFunSpec with StringFixture {
  describe("s") {
    it("t") { _ => succeed }
  }
}

@DoNotDiscover
class FixtureAsyncFunSpecWithDescribe extends FixtureAsyncFunSpec with AsyncStringFixture {
  describe("s") {
    it("t") { _ => succeed }
  }
}

@DoNotDiscover
class PathAnyFunSpecWithDescribe extends PathAnyFunSpec {
  describe("s") {
    it("t") {}
  }
}

@DoNotDiscover
class AnyFreeSpecWithDash extends AnyFreeSpec {
  "s" - {
    "t" in { succeed }
  }
}

@DoNotDiscover
class AsyncFreeSpecWithDash extends AsyncFreeSpec {
  "s" - {
    "t" in { succeed }
  }
}

@DoNotDiscover
class FixtureAnyFreeSpecWithDash extends FixtureAnyFreeSpec with StringFixture {
  "s" - {
    "t" in { _ => succeed }
  }
}

@DoNotDiscover
class FixtureAsyncFreeSpecWithDash extends FixtureAsyncFreeSpec with AsyncStringFixture {
  "s" - {
    "t" in { _ => succeed }
  }
}

@DoNotDiscover
class PathAnyFreeSpecWithDash extends PathAnyFreeSpec {
  "s" - {
    "t" in {}
  }
}

@DoNotDiscover
class AnyWordSpecWithWhenAndShould extends AnyWordSpec {
  "s" when {
    "u" should {
      "t" in { succeed }
    }
  }
}

@DoNotDiscover
class AsyncWordSpecWithWhenAndShould extends AsyncWordSpec {
  "s" when {
    "u" should {
      "t" in { succeed }
    }
  }
}

@DoNotDiscover
class FixtureAnyWordSpecWithWhenAndShould extends FixtureAnyWordSpec with StringFixture {
  "s" when {
    "u" should {
      "t" in { _ => succeed }
    }
  }
}

@DoNotDiscover
class FixtureAsyncWordSpecWithWhenAndShould extends FixtureAsyncWordSpec with AsyncStringFixture {
  "s" when {
    "u" should {
      "t" in { _ => succeed }
    }
  }
}

@DoNotDiscover
class AnyWordSpecWithCan extends AnyWordSpec {
  "s" can {
    "t" in { succeed }
  }
}

@DoNotDiscover
class AsyncWordSpecWithCan extends AsyncWordSpec {
  "s" can {
    "t" in { succeed }
  }
}

@DoNotDiscover
class FixtureAnyWordSpecWithCan extends FixtureAnyWordSpec with StringFixture {
  "s" can {
    "t" in { _ => succeed }
  }
}

@DoNotDiscover
class FixtureAsyncWordSpecWithCan extends FixtureAsyncWordSpec with AsyncStringFixture {
  "s" can {
    "t" in { _ => succeed }
  }
}

@DoNotDiscover
class AnyFlatSpecWithSubject extends AnyFlatSpec {
  "s" should "t" in { succeed }
  it must "u" in { succeed }
}

@DoNotDiscover
class AsyncFlatSpecWithSubject extends AsyncFlatSpec {
  "s" should "t" in { succeed }
  it must "u" in { succeed }
}

@DoNotDiscover
class FixtureAnyFlatSpecWithSubject extends FixtureAnyFlatSpec with StringFixture {
  "s" should "t" in { _ => succeed }
  it must "u" in { _ => succeed }
}

@DoNotDiscover
class FixtureAsyncFlatSpecWithSubject extends FixtureAsyncFlatSpec with AsyncStringFixture {
  "s" should "t" in { _ => succeed }
  it must "u" in { _ => succeed }
}

@DoNotDiscover
class AnyFlatSpecWithBehaviorOf extends AnyFlatSpec {
  behavior of "s"
  it can "t" in { succeed }
}

@DoNotDiscover
class AsyncFlatSpecWithBehaviorOf extends AsyncFlatSpec {
  behavior of "s"
  it can "t" in { succeed }
}

@DoNotDiscover
class FixtureAnyFlatSpecWithBehaviorOf extends FixtureAnyFlatSpec with StringFixture {
  behavior of "s"
  it can "t" in { _ => succeed }
}

@DoNotDiscover
class FixtureAsyncFlatSpecWithBehaviorOf extends FixtureAsyncFlatSpec with AsyncStringFixture {
  behavior of "s"
  it can "t" in { _ => succeed }
}

@DoNotDiscover
class AnyFeatureSpecWithScenario extends AnyFeatureSpec {
  Feature("s") {
    Scenario("t") { succeed }
  }
}

@DoNotDiscover
class FixtureAnyFeatureSpecWithScenario extends FixtureAnyFeatureSpec with StringFixture {
  Feature("s") {
    Scenario("t") { _ => succeed }
  }
}

@DoNotDiscover
class AsyncFeatureSpecWithScenario extends AsyncFeatureSpec {
  Feature("s") {
    Scenario("t") { succeed }
  }
}

@DoNotDiscover
class FixtureAsyncFeatureSpecWithScenario extends FixtureAsyncFeatureSpec with AsyncStringFixture {
  Feature("s") {
    Scenario("t") { _ => succeed }
  }
}

@DoNotDiscover
class AnyFeatureSpecWithIgnoredScenario extends AnyFeatureSpec {
  Feature("s") {
    ignore("t") { succeed }
  }
}

@DoNotDiscover
class AsyncFeatureSpecWithIgnoredScenario extends AsyncFeatureSpec {
  Feature("s") {
    ignore("t") { succeed }
  }
}

@DoNotDiscover
class FixtureAnyFeatureSpecWithIgnoredScenario extends FixtureAnyFeatureSpec with StringFixture {
  Feature("s") {
    ignore("t") { _ => succeed }
  }
}

@DoNotDiscover
class FixtureAsyncFeatureSpecWithIgnoredScenario extends FixtureAsyncFeatureSpec with AsyncStringFixture {
  Feature("s") {
    ignore("t") { _ => succeed }
  }
}

@DoNotDiscover
class RefSpecWithObject extends RefSpec {
  object `s v` {
    def `t u`: Unit = ()
  }
}

@DoNotDiscover
class AnyWordSpecWithWhichAndThat extends AnyWordSpec {
  def provides = afterWord("provides")
  "s" should {
    "u" which {
      "t" in { succeed }
    }
    "v" that {
      "t" in { succeed }
    }
    "w" which provides {
      "t" in { succeed }
    }
    "x" that provides {
      "t" in { succeed }
    }
  }
}

@DoNotDiscover
class AsyncWordSpecWithWhichAndThat extends AsyncWordSpec {
  def provides = afterWord("provides")
  "s" should {
    "u" which {
      "t" in { succeed }
    }
    "v" that {
      "t" in { succeed }
    }
    "w" which provides {
      "t" in { succeed }
    }
    "x" that provides {
      "t" in { succeed }
    }
  }
}

@DoNotDiscover
class FixtureAnyWordSpecWithWhichAndThat extends FixtureAnyWordSpec with StringFixture {
  def provides = afterWord("provides")
  "s" should {
    "u" which {
      "t" in { _ => succeed }
    }
    "v" that {
      "t" in { _ => succeed }
    }
    "w" which provides {
      "t" in { _ => succeed }
    }
    "x" that provides {
      "t" in { _ => succeed }
    }
  }
}

@DoNotDiscover
class FixtureAsyncWordSpecWithWhichAndThat extends FixtureAsyncWordSpec with AsyncStringFixture {
  def provides = afterWord("provides")
  "s" should {
    "u" which {
      "t" in { _ => succeed }
    }
    "v" that {
      "t" in { _ => succeed }
    }
    "w" which provides {
      "t" in { _ => succeed }
    }
    "x" that provides {
      "t" in { _ => succeed }
    }
  }
}

@DoNotDiscover
class AnyFlatSpecWithRegisterTest extends AnyFlatSpec {
  registerTest("can open a file") { succeed }
  registerIgnoredTest("should close it") { succeed }
}

@DoNotDiscover
class AsyncFlatSpecWithRegisterTest extends AsyncFlatSpec {
  registerAsyncTest("can open a file") { succeed }
  registerIgnoredAsyncTest("should close it") { succeed }
}

@DoNotDiscover
class FixtureAnyFlatSpecWithRegisterTest extends FixtureAnyFlatSpec with StringFixture {
  registerTest("can open a file") { _ => succeed }
  registerIgnoredTest("should close it") { _ => succeed }
}

@DoNotDiscover
class FixtureAsyncFlatSpecWithRegisterTest extends FixtureAsyncFlatSpec with AsyncStringFixture {
  registerAsyncTest("can open a file") { _ => succeed }
  registerIgnoredAsyncTest("should close it") { _ => succeed }
}
