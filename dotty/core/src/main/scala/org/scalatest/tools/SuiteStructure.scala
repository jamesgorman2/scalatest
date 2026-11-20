package org.scalatest.tools

import org.scalatest.ConfigMapWrapperSuite
import org.scalatest.Filter
import org.scalatest.RegisteredNode
import org.scalatest.RegisteredScope
import org.scalatest.RegisteredTest
import org.scalatest.Suite
import org.scalatest.TestsBeforeNestedSuites
import org.scalatest.events.SuiteId
import org.scalatest.events.TestName

/** A scope's `text` is the text it is registered with, which may hold more words than its name. */
private[scalatest] enum ScopeOrTest {
  case Scope(name: String, text: String, label: String, scopesAndTests: List[ScopeOrTest])
  case Test(name: String, testName: TestName, label: String, ignored: Boolean = false)
}

/** What `Filter.apply` says of one test: whether the filter drops it, and whether it ignores it. */
private[scalatest] final case class FilterVerdict(isDropped: Boolean, isIgnored: Boolean)

private[scalatest] object FilterVerdict {

  def of(filtered: (Boolean, Boolean)): FilterVerdict = FilterVerdict(filtered._1, filtered._2)
}

/**
 * A suite's structure after filtering, before its nodes have IDs. Each list is in declared order.
 *
 * @param filteredTests for a suite with a registration tree, whether the filter drops and whether it ignores each test
 *                      of the tree, as `Filter.apply` says it for one test
 */
private[scalatest] final case class SuiteStructure(
  instance: Suite,
  name: String,
  suiteId: SuiteId,
  className: String,
  scopesAndTests: List[ScopeOrTest],
  nestedSuites: List[SuiteStructure],
  testsBeforeNestedSuites: Boolean,
  filteredTests: Map[TestName, FilterVerdict] = Map.empty
)

private[scalatest] object SuiteStructure {

  /**
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
        val filteredTests =
          testNamesIn(registered).map(testName => testName -> FilterVerdict.of(filter(testName.value, tags, suiteId))).toMap
        (keptByFilter(registered, filteredTests), filteredTests)
      case None => (ofTestNames(filter(readFrom.testNames, readFrom.tags, suiteId)), Map.empty[TestName, FilterVerdict])
    }
    SuiteStructure(
      suite,
      readFrom.suiteName,
      SuiteId(suiteId),
      Suite.getSuiteClassName(readFrom),
      scopesAndTests,
      if (filter.excludeNestedSuites) Nil else readFrom.nestedSuites.toList.map(read(_, filter)),
      readFrom.isInstanceOf[TestsBeforeNestedSuites],
      filteredTests
    )
  }

  /**
   * The tests of a suite with no registration tree, each labelled as `Suite.runTests` labels a test it ignores. Each
   * name is paired with whether the filter ignores it, as `Filter.apply` pairs the names it keeps.
   */
  def ofTestNames(testNames: Seq[(String, Boolean)]): List[ScopeOrTest] =
    testNames.toList.map { (testName, isIgnored) =>
      val unregistered = RegisteredNode.unregisteredTest(testName)
      ScopeOrTest.Test(unregistered.name, TestName(unregistered.testName), unregistered.label, isIgnored)
    }

  /** Keeps every scope, as the engine opens a scope whose tests the filter all drops. */
  def keptByFilter(registered: List[RegisteredNode], verdictOn: TestName => FilterVerdict): List[ScopeOrTest] = {
    registered.flatMap {
      case RegisteredScope(name, text, label, nodes) => Some(ScopeOrTest.Scope(name, text, label, keptByFilter(nodes, verdictOn)))
      case RegisteredTest(name, registeredName, label, labelWhenIgnored) =>
        val testName = TestName(registeredName)
        val verdict = verdictOn(testName)
        if (verdict.isDropped) {
          None
        } else {
          Some(ScopeOrTest.Test(name, testName, if (verdict.isIgnored) labelWhenIgnored else label, verdict.isIgnored))
        }
    }
  }

  /**
   * A registration tree's scopes, holding only the tests `latest` holds, as `latest` holds them, and the test named
   * `added`, which is not marked ignored. `None` where the tree holds no test of that name.
   */
  def withTestAdded(registered: List[RegisteredNode], latest: List[ScopeOrTest], added: TestName): Option[List[ScopeOrTest]] = {
    val latestTests = testsIn(latest).map(latestTest => latestTest.testName -> latestTest).toMap
    // Recurses once for each scope nested in another, as registering them does.
    def nodesIn(nodes: List[RegisteredNode]): List[ScopeOrTest] =
      nodes.flatMap {
        case RegisteredScope(name, text, label, scopesAndTests) => Some(ScopeOrTest.Scope(name, text, label, nodesIn(scopesAndTests)))
        case RegisteredTest(name, registeredName, label, _) =>
          val testName = TestName(registeredName)
          latestTests.get(testName).orElse(Option.when(testName == added)(ScopeOrTest.Test(name, testName, label, ignored = false)))
      }
    Option.when(testNamesIn(registered).contains(added))(nodesIn(registered))
  }

  // Recurses once for each scope nested in another, as registering them does.
  def testsIn(scopesAndTests: List[ScopeOrTest]): List[ScopeOrTest.Test] =
    scopesAndTests.flatMap {
      case test: ScopeOrTest.Test => List(test)
      case ScopeOrTest.Scope(_, _, _, nodes) => testsIn(nodes)
    }

  // Recurses once for each scope nested in another, as registering them does.
  def testNamesIn(registered: List[RegisteredNode]): List[TestName] =
    registered.flatMap {
      case RegisteredScope(_, _, _, nodes) => testNamesIn(nodes)
      case RegisteredTest(_, testName, _, _) => List(TestName(testName))
    }
}
