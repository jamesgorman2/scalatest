package org.scalatest.tools

import org.scalatest.DeferredAbortedSuite
import org.scalatest.Filter
import org.scalatest.RegisteredNode
import org.scalatest.RegisteredScope
import org.scalatest.RegisteredTest
import org.scalatest.Suite
import org.scalatest.Suites
import org.scalatest.Tag
import org.scalatest.TestsBeforeNestedSuites
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scala.collection.immutable.ListSet

final class SuiteStructureSpec extends AnyWordSpec with Matchers {

  private object Slow extends Tag("samples.Slow")

  private val excludingSlow = Filter(None, Set("org.scalatest.Ignore", "samples.Slow"))

  private final class ScopedSuite extends AnyFunSpec {
    describe("first") {
      it("runs") {}
      it("is slow", Slow) {}
    }
    describe("second") {
      it("is slow too", Slow) {}
    }
    describe("third") {
      ignore("is ignored") {}
    }
  }

  private final class ScenarioSuite extends AnyFeatureSpec {
    Feature("s") {
      Scenario("runs") {}
      ignore("is ignored") {}
    }
  }

  private final class UnregisteredSuite extends Suite {
    override def testNames: Set[String] = ListSet("b", "a", "c", "test: d")
    override def tags: Map[String, Set[String]] = Map("c" -> Set("samples.Slow"), "a" -> Set("org.scalatest.Ignore"))
  }

  private final class Inner(testName: String) extends AnyFunSuite {
    test(testName, Slow) {}
    test("kept") {}
  }

  private final class TestsFirst(nested: Suite*) extends Suites(nested*) with TestsBeforeNestedSuites

  private def keptTest(name: String, testName: String, label: String): ScopeOrTest = ScopeOrTest.Test(name, testName, label, ignored = false)

  private def ignoredTest(name: String, testName: String, label: String): ScopeOrTest = ScopeOrTest.Test(name, testName, label, ignored = true)

  "The structure read from a suite with a registration tree" should {
    "hold each scope and each test the filter keeps, in the order registered, marking each test the filter ignores as ignored" in {
      SuiteStructure.read(new ScopedSuite, excludingSlow).scopesAndTests shouldBe List(
        ScopeOrTest.Scope("first", "first", "first", List(keptTest("runs", "first runs", "- runs"))),
        ScopeOrTest.Scope("second", "second", "second", Nil),
        ScopeOrTest.Scope("third", "third", "third", List(ignoredTest("is ignored", "third is ignored", "- is ignored")))
      )
    }

    "label a test that runs as its closing event is labelled, and one the filter ignores as its TestIgnored is" in {
      SuiteStructure.read(new ScenarioSuite, Filter.default).scopesAndTests shouldBe List(
        ScopeOrTest.Scope(
          "s",
          "Feature: s",
          "Feature: s",
          List(
            keptTest("runs", "Feature: s Scenario: runs", "Scenario: runs"),
            ignoredTest("is ignored", "Feature: s Scenario: is ignored", "- Scenario: is ignored")
          )
        )
      )
    }
  }

  "The structure read from a suite with no registration tree" should {
    "hold a test for each test name the filter keeps, in the order of the suite's test names, marked ignored where the filter ignores it, and no scope" in {
      SuiteStructure.read(new UnregisteredSuite, excludingSlow).scopesAndTests shouldBe
        List(keptTest("b", "b", "- b"), ignoredTest("a", "a", "- a"), keptTest("test: d", "test: d", "- d"))
    }
  }

  "The structure read from a suite" should {
    "hold the suite's name and suite ID, and the instance it was read from" in {
      val suite = new ScenarioSuite
      val structure = SuiteStructure.read(suite, Filter.default)
      structure.instance should be theSameInstanceAs suite
      (structure.name, structure.suiteId) shouldBe ((suite.suiteName, classOf[ScenarioSuite].getName))
    }

    "take the suite's class name from the class of the instance" in {
      SuiteStructure.read(new ScenarioSuite, Filter.default).className shouldBe classOf[ScenarioSuite].getName
    }

    "take the class name of a suite that stands for one whose construction failed from the class that failed" in {
      val standIn = new DeferredAbortedSuite("samples.BrokenCtorSpec", "samples.BrokenCtorSpec", new IllegalStateException("no config"))
      val structure = SuiteStructure.read(standIn, Filter.default)
      (structure.name, structure.suiteId, structure.className, structure.scopesAndTests, structure.nestedSuites) shouldBe
        (("BrokenCtorSpec", "samples.BrokenCtorSpec", "samples.BrokenCtorSpec", Nil, Nil))
    }

    "hold each nested suite in the order the suite nests them, read from that instance with the same filter" in {
      val first = new Inner("one")
      val second = new Inner("two")
      val structure = SuiteStructure.read(new Suites(first, second), excludingSlow)
      structure.nestedSuites.map(_.scopesAndTests) shouldBe List(List(keptTest("kept", "kept", "- kept")), List(keptTest("kept", "kept", "- kept")))
      structure.nestedSuites.map(_.instance).zip(List(first, second)).foreach((read, nested) => read should be theSameInstanceAs nested)
    }

    "hold the suites nested in its nested suites" in {
      val innermost = new Inner("one")
      val structure = SuiteStructure.read(new Suites(new Suites(innermost)), Filter.default)
      structure.nestedSuites.flatMap(_.nestedSuites).map(_.instance).foreach(_ should be theSameInstanceAs innermost)
      structure.nestedSuites.flatMap(_.nestedSuites).size shouldBe 1
    }

    "hold no nested suite where the filter excludes nested suites" in {
      val excludingNestedSuites = Filter(None, Set("org.scalatest.Ignore"), excludeNestedSuites = true)
      SuiteStructure.read(new Suites(new Inner("one")), excludingNestedSuites).nestedSuites shouldBe Nil
    }

    "say that its scopes and tests come before its nested suites only for a suite that runs its tests first" in {
      val structures = List(new TestsFirst(new Inner("one")), new Suites(new Inner("one"))).map(SuiteStructure.read(_, Filter.default))
      structures.map(_.testsBeforeNestedSuites) shouldBe List(true, false)
    }
  }

  "A test added to a suite's latest structure for an event" should {
    "be added unmarked, beside the tests the latest structure holds with their marks" in {
      val registered = List(
        RegisteredScope("s", "s", "s", List(RegisteredTest("ignored", "s ignored", "- ignored", "- ignored"), RegisteredTest("added", "s added", "- added", "- added")))
      )
      val latest = List(ScopeOrTest.Scope("s", "s", "s", List(ignoredTest("ignored", "s ignored", "- ignored"))))
      SuiteStructure.withTestAdded(registered, latest, "s added") shouldBe Some(
        List(ScopeOrTest.Scope("s", "s", "s", List(ignoredTest("ignored", "s ignored", "- ignored"), keptTest("added", "s added", "- added"))))
      )
    }
  }

  "The tests of a suite with no registration tree" should {
    "mark a test ignored where its name is paired with true" in {
      SuiteStructure.ofTestNames(List("b" -> false, "a" -> true)) shouldBe List(keptTest("b", "b", "- b"), ignoredTest("a", "a", "- a"))
    }
  }

  "The name of a FlatSpec test" should {
    "be its text without the verb before it" in {
      List("should pop", "must pop", "can pop").map(RegisteredNode.flatSpecTestName) shouldBe List("pop", "pop", "pop")
    }

    "be its whole text where it is registered with no verb" in {
      List("pops", "shouldering on", "should").map(RegisteredNode.flatSpecTestName) shouldBe List("pops", "shouldering on", "should")
    }
  }
}
