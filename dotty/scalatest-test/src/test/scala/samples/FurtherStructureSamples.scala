package samples

import org.scalatest.Args
import org.scalatest.BeforeAndAfterAll
import org.scalatest.ConfigMapWrapperSuite
import org.scalatest.DoNotDiscover
import org.scalatest.OneInstancePerTest
import org.scalatest.Status
import org.scalatest.SucceededStatus
import org.scalatest.Suite
import org.scalatest.Suites
import org.scalatest.WrapWith
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.funspec.AsyncFunSpec
import org.scalatest.funsuite.AnyFunSuite
import scala.collection.immutable.ListSet

@DoNotDiscover
@WrapWith(classOf[ConfigMapWrapperSuite])
class ConfigSpec(configMap: Map[String, Any]) extends AnyFunSpec {

  describe("A config") {
    it("reads") {
      succeed
    }
  }
}

// Registers test remote where its config map holds db, and test local where it does not.
@DoNotDiscover
@WrapWith(classOf[ConfigMapWrapperSuite])
class ConfigDependentSpec(configMap: Map[String, Any]) extends AnyFunSpec {

  describe("A config") {
    if (configMap.contains("db")) {
      it("remote") {
        succeed
      }
    } else {
      it("local") {
        succeed
      }
    }
  }
}

// Registers test reads whatever its config map, then test remote where the map holds db, and test local where it does not.
@DoNotDiscover
@WrapWith(classOf[ConfigMapWrapperSuite])
class ConfigDependentWithSharedTestSpec(configMap: Map[String, Any]) extends AnyFunSpec {

  describe("A config") {
    it("reads") {
      succeed
    }
    if (configMap.contains("db")) {
      it("remote") {
        succeed
      }
    } else {
      it("local") {
        succeed
      }
    }
  }
}

@DoNotDiscover
@WrapWith(classOf[ConfigMapWrapperSuite])
class ConfigWithoutDbFailsSpec(configMap: Map[String, Any]) extends AnyFunSpec {

  if (!configMap.contains("db")) {
    throw new IllegalStateException("no db")
  }

  describe("A config") {
    it("reads") {
      succeed
    }
  }
}

@DoNotDiscover
@WrapWith(classOf[ConfigMapWrapperSuite])
class ConfigWithDbFailsSpec(configMap: Map[String, Any]) extends AnyFunSpec {

  if (configMap.contains("db")) {
    throw new IllegalStateException("no db")
  }

  describe("A config") {
    it("reads") {
      succeed
    }
  }
}

@DoNotDiscover
@WrapWith(classOf[ConfigMapWrapperSuite])
class ConfigSetupFailsSpec(configMap: Map[String, Any]) extends AnyFunSpec with BeforeAndAfterAll {

  override protected def beforeAll(): Unit = throw new RuntimeException("database unreachable")

  describe("A config") {
    it("reads") {
      succeed
    }
  }
}

// A suite with no registration tree whose one test is remote where its config map holds db, and local where it does not.
@DoNotDiscover
@WrapWith(classOf[ConfigMapWrapperSuite])
class ConfigNamedTestSuite(configMap: Map[String, Any]) extends Suite {

  override def testNames: Set[String] = ListSet(if (configMap.contains("db")) "remote" else "local")

  override protected def runTest(testName: String, args: Args): Status = SucceededStatus
}

// Its runTests also runs test b, which the filter drops where samples.Slow is excluded.
@DoNotDiscover
class RunsADroppedTestSuite extends AnyFunSuite {

  test("a") {
    succeed
  }

  test("b", Slow) {
    succeed
  }

  override protected def runTests(testName: Option[String], args: Args): Status = {
    val status = super.runTests(testName, args)
    status.succeeds()
    runTest("b", args)
  }
}

// Nests ConfigDependentSpec, run through ConfigMapWrapperSuite.
@DoNotDiscover
class NestsConfigDependentSpec extends Suites(new ConfigMapWrapperSuite(classOf[ConfigDependentSpec]))

// Registers test remote where its config map holds db, and test local where it does not.
@DoNotDiscover
@WrapWith(classOf[ConfigMapWrapperSuite])
class AsyncConfigDependentSpec(configMap: Map[String, Any]) extends AsyncFunSpec {

  describe("A config") {
    if (configMap.contains("db")) {
      it("remote") {
        succeed
      }
    } else {
      it("local") {
        succeed
      }
    }
  }
}

// Runs each test in an instance of its own, each registering the same tests.
@DoNotDiscover
class OneInstancePerTestSuite extends AnyFunSuite with OneInstancePerTest {

  test("a") {
    succeed
  }

  test("b") {
    succeed
  }
}

// Its tags override tags test b samples.Slow, which b is not registered with.
@DoNotDiscover
class TagsOverrideSuite extends AnyFunSuite {

  test("a") {
    succeed
  }

  test("b") {
    succeed
  }

  override def tags: Map[String, Set[String]] = super.tags.updated("b", Set("samples.Slow"))
}
