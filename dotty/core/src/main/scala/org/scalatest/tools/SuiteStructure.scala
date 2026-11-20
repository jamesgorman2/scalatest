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

private[scalatest] enum ScopeOrTest {
  case Scope(name: String, registeredText: String, label: String, scopesAndTests: List[ScopeOrTest])
  case Test(name: String, scalaTestName: TestName, label: String, ignored: Boolean = false)
}

private[scalatest] final case class FilterVerdict(isDropped: Boolean, isIgnored: Boolean)

private[scalatest] object FilterVerdict {

  def of(filtered: (Boolean, Boolean)): FilterVerdict = FilterVerdict(filtered._1, filtered._2)
}

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

  def read(suite: Suite, filter: Filter): SuiteStructure = {
    // A ConfigMapWrapperSuite forwards its names, tests, tags and nested suites to the instance it builds with an empty
    // config map, but not its registration tree or its class name.
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
      // Recurses no deeper than running the nested suites does.
      if (filter.excludeNestedSuites) Nil else readFrom.nestedSuites.toList.map(read(_, filter)),
      readFrom.isInstanceOf[TestsBeforeNestedSuites],
      filteredTests
    )
  }

  def ofTestNames(testNames: Seq[(String, Boolean)]): List[ScopeOrTest] =
    testNames.toList.map { (testName, isIgnored) =>
      val unregistered = RegisteredNode.unregisteredTest(testName)
      ScopeOrTest.Test(unregistered.name, TestName(unregistered.scalaTestName), unregistered.label, isIgnored)
    }

  def keptByFilter(registered: List[RegisteredNode], verdictOn: TestName => FilterVerdict): List[ScopeOrTest] = {
    registered.flatMap {
      // Kept even where the filter drops all its tests, since the engine still opens it.
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

  def withTestAdded(registered: List[RegisteredNode], latest: List[ScopeOrTest], added: TestName): Option[List[ScopeOrTest]] = {
    val latestTests = testsIn(latest).map(latestTest => latestTest.scalaTestName -> latestTest).toMap
    def nodesIn(nodes: List[RegisteredNode]): List[ScopeOrTest] =
      nodes.flatMap {
        // Recurses no deeper than registering the scopes did.
        case RegisteredScope(name, text, label, scopesAndTests) => Some(ScopeOrTest.Scope(name, text, label, nodesIn(scopesAndTests)))
        case RegisteredTest(name, registeredName, label, _) =>
          val testName = TestName(registeredName)
          latestTests.get(testName).orElse(Option.when(testName == added)(ScopeOrTest.Test(name, testName, label, ignored = false)))
      }
    Option.when(testNamesIn(registered).contains(added))(nodesIn(registered))
  }

  def testsIn(scopesAndTests: List[ScopeOrTest]): List[ScopeOrTest.Test] =
    scopesAndTests.flatMap {
      case test: ScopeOrTest.Test => List(test)
      // Recurses no deeper than registering the scopes did.
      case ScopeOrTest.Scope(_, _, _, nodes) => testsIn(nodes)
    }

  def testNamesIn(registered: List[RegisteredNode]): List[TestName] =
    registered.flatMap {
      // Recurses no deeper than registering the scopes did.
      case RegisteredScope(_, _, _, nodes) => testNamesIn(nodes)
      case RegisteredTest(_, testName, _, _) => List(TestName(testName))
    }
}
