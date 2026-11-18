package samples

import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.featurespec.AsyncFeatureSpec
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.propspec.AnyPropSpec
import org.scalatest.refspec.RefSpec
import org.scalatest.wordspec.AnyWordSpec

final class CalcFunSuite extends AnyFunSuite {
  test("adds") {}
}

final class CalcPropSpec extends AnyPropSpec {
  property("adds") {}
}

final class CalcFunSpec extends AnyFunSpec {
  describe("A calculator") {
    it("adds") {}
  }
}

final class CalcFreeSpec extends AnyFreeSpec {
  "A calculator" - {
    "adds" in {}
  }
}

final class CalcWordSpec extends AnyWordSpec {
  "A calculator" when {
    "empty" should {
      "add" in {}
    }
  }
}

final class CalcFlatSpec extends AnyFlatSpec {
  "A calculator" should "add" in {}
}

final class CalcFeatureSpec extends AnyFeatureSpec {
  Feature("Addition") {
    Scenario("adds") {}
  }
}

final class CalcAsyncFeatureSpec extends AsyncFeatureSpec {
  Feature("Addition") {
    Scenario("adds") {
      succeed
    }
  }
}

final class CalcRefSpec extends RefSpec {
  object `A calculator` {
    def `adds two`: Unit = ()
  }
}
