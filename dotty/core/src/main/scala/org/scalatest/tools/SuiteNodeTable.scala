package org.scalatest.tools

import org.scalatest.Suite
import org.scalatest.events.NodeId
import org.scalatest.events.StructureNode

/** A scope among its parent's scopes: its name, and its place, from 0, among those of that name. */
private[scalatest] final case class ScopePlace(name: String, placeAmongScopesOfItsName: Int)

/**
 * The node IDs a run's test event log gives one suite node: the suite's own, and those of its tests, scopes and
 * nested suites.
 *
 * Not a case class: the suite instance is user code, whose `equals`, `hashCode` and `toString` must not be called.
 *
 * @param instance the suite instance the suite's structure was read from
 * @param tests each test's node, by test name
 * @param scopes each scope's node, by its path: the place of each scope from the suite down to it
 * @param nestedSuites each nested suite's table, at its position in `nestedSuites`
 */
private[scalatest] final class SuiteNodeTable(
  val instance: Suite,
  val node: NodeId,
  val suiteId: String,
  val className: String,
  val tests: Map[String, NodeId],
  val scopes: Map[List[ScopePlace], NodeId],
  val nestedSuites: Vector[SuiteNodeTable]
)

private[scalatest] object SuiteNodeTable {

  /**
   * Mints a node for a top-level suite and for each node of its structure. Returns the suite's table, and the
   * nodes as a `structure` event holds them: in depth-first pre-order, each node's children in declared order.
   */
  def minted(structure: SuiteStructure, mint: () => NodeId): (SuiteNodeTable, Vector[StructureNode]) = {
    val minting = new Minting(mint)
    val table = minting.suite(structure, None, None)
    (table, minting.nodes.result())
  }

  private final class Minting(mint: () => NodeId) {

    val nodes = Vector.newBuilder[StructureNode]

    // Recurses once for each suite nested in another, as reading the structure does.
    def suite(structure: SuiteStructure, parent: Option[NodeId], position: Option[Int]): SuiteNodeTable = {
      val id = mint()
      nodes += StructureNode.Suite(id, parent, position, structure.name, structure.suiteId, structure.className)
      val scopesAndTests = new ScopesAndTests
      def nestedSuitesFrom(firstPosition: Int): Vector[SuiteNodeTable] =
        structure.nestedSuites.toVector.zipWithIndex.map((nested, index) => suite(nested, Some(id), Some(firstPosition + index)))
      val nestedSuites =
        if (structure.testsBeforeNestedSuites) {
          scopesAndTests.add(structure.scopesAndTests, id, 0, Nil)
          nestedSuitesFrom(structure.scopesAndTests.size)
        } else {
          val before = nestedSuitesFrom(0)
          scopesAndTests.add(structure.scopesAndTests, id, structure.nestedSuites.size, Nil)
          before
        }
      new SuiteNodeTable(
        structure.instance,
        id,
        structure.suiteId,
        structure.className,
        scopesAndTests.tests.result(),
        scopesAndTests.scopes.result(),
        nestedSuites
      )
    }

    private final class ScopesAndTests {

      val tests = Map.newBuilder[String, NodeId]
      val scopes = Map.newBuilder[List[ScopePlace], NodeId]

      // Recurses once for each scope nested in another, as registering them does.
      def add(children: List[ScopeOrTest], parent: NodeId, firstPosition: Int, pathOfParent: List[ScopePlace]): Unit = {
        children.zipWithIndex.foldLeft(Map.empty[String, Int]) {
          case (scopesSoFarByName, (ScopeOrTest.Test(name, testName, label), index)) =>
            val id = mint()
            nodes += StructureNode.Test(id, parent, firstPosition + index, name, label)
            tests += testName -> id
            scopesSoFarByName
          case (scopesSoFarByName, (ScopeOrTest.Scope(name, label, scopesAndTests), index)) =>
            val id = mint()
            val place = scopesSoFarByName.getOrElse(name, 0)
            val path = pathOfParent :+ ScopePlace(name, place)
            nodes += StructureNode.Scope(id, parent, firstPosition + index, name, label)
            scopes += path -> id
            add(scopesAndTests, id, 0, path)
            scopesSoFarByName.updated(name, place + 1)
        }
      }
    }
  }
}
