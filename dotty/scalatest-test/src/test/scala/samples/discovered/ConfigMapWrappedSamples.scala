package samples.discovered

import org.scalatest.ConfigMapWrapperSuite
import org.scalatest.WrapWith
import org.scalatest.funspec.AnyFunSpec

// Discoverable, as the other samples of this package are: a test copies these class files to a runpath of its own for discovery to find.

@WrapWith(classOf[ConfigMapWrapperSuite])
class ConfigSpec(configMap: Map[String, Any]) extends AnyFunSpec {

  describe("A config") {
    it("reads") {
      succeed
    }
  }
}

// Its constructor throws, given a config map without db, only on a thread of the group a test names for it, so that
// it constructs wherever else it is discovered.
@WrapWith(classOf[ConfigMapWrapperSuite])
class ConfigWithoutDbFailsSpec(configMap: Map[String, Any]) extends AnyFunSpec {

  if (!configMap.contains("db") && Thread.currentThread.getThreadGroup.getName == BrokenCtorSpec.threadGroupItsConstructorThrowsIn) {
    throw new IllegalStateException("no db")
  }

  describe("A config") {
    it("reads") {
      succeed
    }
  }
}
