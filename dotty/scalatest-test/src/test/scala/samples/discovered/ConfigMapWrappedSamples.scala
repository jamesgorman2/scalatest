package samples.discovered

import org.scalatest.ConfigMapWrapperSuite
import org.scalatest.WrapWith
import org.scalatest.funspec.AnyFunSpec

// Not @DoNotDiscover: a test copies this package's class files to a runpath of its own for discovery to find.

@WrapWith(classOf[ConfigMapWrapperSuite])
class ConfigSpec(configMap: Map[String, Any]) extends AnyFunSpec {

  describe("A config") {
    it("reads") {
      succeed
    }
  }
}

@WrapWith(classOf[ConfigMapWrapperSuite])
class ConfigWithoutDbFailsSpec(configMap: Map[String, Any]) extends AnyFunSpec {

  // Throws only in the thread group a test names: other runs discover this suite too, and must construct it.
  if (!configMap.contains("db") && Thread.currentThread.getThreadGroup.getName == ThrowingConstructorSpec.threadGroupItsConstructorThrowsIn) {
    throw new IllegalStateException("no db")
  }

  describe("A config") {
    it("reads") {
      succeed
    }
  }
}
