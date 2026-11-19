package samples

import org.scalatest.funspec.AnyFunSpec

final class OutcomesSpec extends AnyFunSpec {
  describe("A calculator") {
    describe("when adding") {
      it("adds") {
        assert(1 + 1 == 2)
      }
      it("fails") {
        assert(1 + 1 == 3)
      }
      it("cancels") {
        cancel("no database")
      }
      ignore("is ignored") {
        assert(1 + 1 == 2)
      }
      it("is pending") {
        pending
      }
    }
  }
  describe("A queue") {
    pending
  }
}
