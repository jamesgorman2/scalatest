package compatibility.fixture

import org.scalatest.ConfigMapWrapperSuite
import org.scalatest.WrapWith
import org.scalatest.funspec.AnyFunSpec

@WrapWith(classOf[ConfigMapWrapperSuite])
class ConfigMapWrappedSpec(configMap: Map[String, Any]) extends AnyFunSpec {
  describe("A scope of a wrapped suite") {
    it("succeeds") {
      assert(configMap.isEmpty)
    }
    it("fails") {
      assert(configMap.nonEmpty)
    }
  }
}
