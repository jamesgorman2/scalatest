package org.scalatest.tools

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
 * @param instance the suite instance the structure was read from
 * @param testsBeforeNestedSuites whether the suite's scopes and tests come before its nested suites in declared order
 */
private[scalatest] final case class SuiteStructure(
  instance: Suite,
  name: String,
  suiteId: String,
  className: String,
  scopesAndTests: List[ScopeOrTest],
  nestedSuites: List[SuiteStructure],
  testsBeforeNestedSuites: Boolean
)

private[scalatest] object SuiteStructure {

  /**
   * Reads a suite's structure from the constructed suite, its nested suites and the run's filter.
   *
   * It calls the suite's own `suiteName`, `suiteId`, `testNames`, `tags` and `nestedSuites`, and those of each
   * nested suite, any of which may throw. It recurses once for each suite nested in another, as running them does.
   */
  def read(suite: Suite, filter: Filter): SuiteStructure = {
    val suiteId = suite.suiteId
    val scopesAndTests = suite.scopesAndTestsOfRegistrationTree match {
      case Some(registered) =>
        val tags = suite.tags
        kept(registered, testName => filter(testName, tags, suiteId))
      case None =>
        filter(suite.testNames, suite.tags, suiteId).map { (testName, _) =>
          val test = RegisteredNode.unregisteredTest(testName)
          ScopeOrTest.Test(test.name, test.testName, test.label)
        }
    }
    SuiteStructure(
      suite,
      suite.suiteName,
      suiteId,
      Suite.getSuiteClassName(suite),
      scopesAndTests,
      if (filter.excludeNestedSuites) Nil else suite.nestedSuites.toList.map(read(_, filter)),
      suite.isInstanceOf[TestsBeforeNestedSuites]
    )
  }

  // Keeps every scope, as the engine opens a scope whose tests the filter all drops, and the tests the filter keeps.
  // `filtered` returns, as `Filter.apply` does for one test, whether the test is dropped and whether it is ignored.
  private def kept(registered: List[RegisteredNode], filtered: String => (Boolean, Boolean)): List[ScopeOrTest] = {
    registered.flatMap {
      case RegisteredScope(name, text, label, nodes) => Some(ScopeOrTest.Scope(name, text, label, kept(nodes, filtered)))
      case RegisteredTest(name, testName, label, labelWhenIgnored) =>
        val (isDropped, isIgnored) = filtered(testName)
        if (isDropped) None else Some(ScopeOrTest.Test(name, testName, if (isIgnored) labelWhenIgnored else label))
    }
  }
}
