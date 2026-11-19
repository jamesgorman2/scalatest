package samples

import org.scalatest.ConfigMapWrapperSuite
import org.scalatest.WrapWith
import org.scalatest.funspec.AnyFunSpec

@WrapWith(classOf[ConfigMapWrapperSuite])
final class WrappedSpec(configMap: Map[String, Any]) extends AnyFunSpec {
  describe("A wrapped scope") {
    it("succeeds") {
      assert(configMap.isEmpty)
    }
    it("also succeeds") {
      assert(configMap.get("db").isEmpty)
    }
  }
}
