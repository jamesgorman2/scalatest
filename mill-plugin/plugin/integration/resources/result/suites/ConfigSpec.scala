package samples

import org.scalatest.ConfigMapWrapperSuite
import org.scalatest.WrapWith
import org.scalatest.funspec.AnyFunSpec

@WrapWith(classOf[ConfigMapWrapperSuite])
final class ConfigSpec(configMap: Map[String, Any]) extends AnyFunSpec {
  describe("A config") {
    if (configMap.contains("db")) {
      it("remote") {
        assert(1 + 1 == 2)
      }
    } else {
      it("local") {
        assert(1 + 1 == 2)
      }
    }
  }
}
