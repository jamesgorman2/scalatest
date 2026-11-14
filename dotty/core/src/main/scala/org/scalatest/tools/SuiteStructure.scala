package org.scalatest.tools

import org.scalatest.ConfigMapWrapperSuite
import org.scalatest.Filter
import org.scalatest.RegisteredNode
import org.scalatest.RegisteredScope
import org.scalatest.RegisteredTest
import org.scalatest.Suite
import org.scalatest.TestsBeforeNestedSuites

/** A scope or test of a suite's structure. A scope's `text` is the text it is registered with, which may hold more words than its name. */
private[scalatest] enum ScopeOrTest {
  case Scope(name: String, text: String, label: String, scopesAndTests: List[ScopeOrTest])
  case Test(name: String, testName: String, label: String)
}

/**
 * A suite's structure after filtering, before its nodes have IDs: the suite, its scopes and tests, and its nested
 * suites, each list in declared order.
 *
 * @param instance the suite instance the structure is for
 * @param testsBeforeNestedSuites whether the suite's scopes and tests come before its nested suites in declared order
 * @param filteredTests for a suite with a registration tree, what the filter said of each test of the tree, as
 *                      `Filter.apply` says it for one test: whether the test is dropped and whether it is ignored
 */
private[scalatest] final case class SuiteStructure(
  instance: Suite,
  name: String,
  suiteId: String,
  className: String,
  scopesAndTests: List[ScopeOrTest],
  nestedSuites: List[SuiteStructure],
  testsBeforeNestedSuites: Boolean,
  filteredTests: Map[String, (Boolean, Boolean)] = Map.empty
)

private[scalatest] object SuiteStructure {

  /**
   * Reads a suite's structure from the constructed suite, its nested suites and the run's filter.
   *
   * It calls the suite's own `suiteName`, `suiteId`, `testNames`, `tags` and `nestedSuites`, and those of each
   * nested suite, any of which may throw. It recurses once for each suite nested in another, as running them does.
   */
  def read(suite: Suite, filter: Filter): SuiteStructure = {
    // A ConfigMapWrapperSuite forwards each of these to its wrapped class's instance built with an empty config map,
    // which is built before the suite starts. Only that instance has a registration tree and the wrapped class's name.
    val readFrom = suite match {
      case wrapper: ConfigMapWrapperSuite => wrapper.instanceWithEmptyConfigMap
      case _ => suite
    }
    val suiteId = readFrom.suiteId
    val (scopesAndTests, filteredTests) = readFrom.scopesAndTestsOfRegistrationTree match {
      case Some(registered) =>
        val tags = readFrom.tags
        val filteredTests = testNamesIn(registered).map(testName => testName -> filter(testName, tags, suiteId)).toMap
        (kept(registered, filteredTests), filteredTests)
      case None => (ofTestNames(filter(readFrom.testNames, readFrom.tags, suiteId).map((testName, _) => testName)), Map.empty)
    }
    SuiteStructure(
      suite,
      readFrom.suiteName,
      suiteId,
      Suite.getSuiteClassName(readFrom),
      scopesAndTests,
      if (filter.excludeNestedSuites) Nil else readFrom.nestedSuites.toList.map(read(_, filter)),
      readFrom.isInstanceOf[TestsBeforeNestedSuites],
      filteredTests
    )
  }

  /** The tests of a suite with no registration tree, in the order given, each labelled as `Suite.runTests` labels a test it ignores. */
  def ofTestNames(testNames: Seq[String]): List[ScopeOrTest] =
    testNames.toList.map { testName =>
      val test = RegisteredNode.unregisteredTest(testName)
      ScopeOrTest.Test(test.name, test.testName, test.label)
    }

  /**
   * Keeps every scope, as the engine opens a scope whose tests the filter all drops, and the tests the filter keeps.
   * `filtered` returns, as `Filter.apply` does for one test, whether the test is dropped and whether it is ignored.
   */
  def kept(registered: List[RegisteredNode], filtered: String => (Boolean, Boolean)): List[ScopeOrTest] = {
    registered.flatMap {
      case RegisteredScope(name, text, label, nodes) => Some(ScopeOrTest.Scope(name, text, label, kept(nodes, filtered)))
      case RegisteredTest(name, testName, label, labelWhenIgnored) =>
        val (isDropped, isIgnored) = filtered(testName)
        if (isDropped) None else Some(ScopeOrTest.Test(name, testName, if (isIgnored) labelWhenIgnored else label))
    }
  }

  /**
   * A registration tree's scopes, holding only the tests `latest` holds, as `latest` holds them, and the test named
   * `added`. `None` where the tree holds no test of that name.
   */
  def withTestAdded(registered: List[RegisteredNode], latest: List[ScopeOrTest], added: String): Option[List[ScopeOrTest]] = {
    val latestTests = testsIn(latest).map(test => test.testName -> test).toMap
    // Recurses once for each scope nested in another, as registering them does.
    def nodesIn(nodes: List[RegisteredNode]): List[ScopeOrTest] =
      nodes.flatMap {
        case RegisteredScope(name, text, label, scopesAndTests) => Some(ScopeOrTest.Scope(name, text, label, nodesIn(scopesAndTests)))
        case RegisteredTest(name, testName, label, _) =>
          latestTests.get(testName).orElse(Option.when(testName == added)(ScopeOrTest.Test(name, testName, label)))
      }
    Option.when(testNamesIn(registered).contains(added))(nodesIn(registered))
  }

  /** The tests among the scopes and tests, and within each scope. It recurses once for each scope nested in another. */
  def testsIn(scopesAndTests: List[ScopeOrTest]): List[ScopeOrTest.Test] =
    scopesAndTests.flatMap {
      case test: ScopeOrTest.Test => List(test)
      case ScopeOrTest.Scope(_, _, _, nodes) => testsIn(nodes)
    }

  /** The test names of a registration tree. */
  def testNamesIn(registered: List[RegisteredNode]): List[String] =
    registered.flatMap {
      case RegisteredScope(_, _, _, nodes) => testNamesIn(nodes)
      case test: RegisteredTest => List(test.testName)
    }
}
