package org.scalatest.tools

import java.util.IdentityHashMap
import org.scalatest.ScopePlace
import org.scalatest.Suite
import org.scalatest.events.NodeId
import org.scalatest.events.StructureNode
import scala.collection.mutable

/** One execution of a suite, scope or test. It has no number until an event naming it is written. */
private[scalatest] final class Execution

/**
 * The executions of one node, numbered from 1 in the order the event log first names them.
 *
 * Read and written only under the emitter's lock.
 */
private[scalatest] final class NodeExecutions {

  private val numbers = new IdentityHashMap[Execution, Integer]
  private var open = List.empty[Execution]
  private var started = Option.empty[Execution]

  /** The execution's number among the node's. An execution not yet numbered takes the next, and is then the node's latest, and open. */
  def numberOf(execution: Execution): Int = {
    Option(numbers.get(execution)) match {
      case Some(number) => number
      case None =>
        val number = numbers.size + 1
        numbers.put(execution, number)
        open = execution :: open
        started = Some(execution)
        number
    }
  }

  def close(execution: Execution): Unit = open = open.filterNot(_ eq execution)

  /** The execution that opened last and has not closed. */
  def openedLast: Option[Execution] = open.headOption

  /** The execution numbered last. */
  def latest: Option[Execution] = started
}

/**
 * The node IDs a run's test event log gives one suite node: the suite's own, and those of its tests, scopes and
 * nested suites. It also keeps the executions of the suite and of its scopes and tests.
 *
 * Not a case class: the suite instance is user code, whose `equals`, `hashCode` and `toString` must not be called.
 *
 * @param instance the suite instance the suite's structure was read from
 * @param tests each test's node, by test name
 * @param scopes each scope's node, by its path: the place of each scope from the suite down to it
 * @param parents the parent of each scope and test
 * @param nestedSuites each nested suite's table, at its position in `nestedSuites`
 */
private[scalatest] final class SuiteNodeTable(
  val instance: Suite,
  val node: NodeId,
  val suiteId: String,
  val className: String,
  val tests: Map[String, NodeId],
  val scopes: Map[List[ScopePlace], NodeId],
  val parents: Map[NodeId, NodeId],
  val nestedSuites: Vector[SuiteNodeTable]
) {

  // Both are read and written only under the emitter's lock.
  private val executionsOfNodes = mutable.HashMap.empty[NodeId, NodeExecutions]
  private var openedBySuiteStarting = Option.empty[Execution]

  /** The node of a scope or test, or none for the suite itself and for a scope or test the suite's structure does not hold. */
  def scopeOrTest(treeNode: TreeNode): Option[NodeId] = {
    treeNode match {
      case TreeNode.OfSuite => None
      case TreeNode.Scope(places) => scopes.get(places)
      case TreeNode.Test(testName) => tests.get(testName)
    }
  }

  def executionsOf(node: NodeId): NodeExecutions = executionsOfNodes.getOrElseUpdate(node, new NodeExecutions)

  /** Keeps the execution the suite's `SuiteStarting` opened for the suite's next run to take. */
  def awaitRun(execution: Execution): Unit = openedBySuiteStarting = Some(execution)

  /** Forgets the execution the suite's `SuiteStarting` opened, where that execution has closed with no run. */
  def closedWithoutRun(execution: Execution): Unit = openedBySuiteStarting = openedBySuiteStarting.filterNot(_ eq execution)

  /** The execution of a run that starts: the one the suite's `SuiteStarting` opened where no run has taken it, and otherwise a new one. */
  def executionOfStartingRun(): Execution = {
    val execution = openedBySuiteStarting.getOrElse(new Execution)
    openedBySuiteStarting = None
    execution
  }
}

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
        scopesAndTests.parents.result(),
        nestedSuites
      )
    }

    private final class ScopesAndTests {

      val tests = Map.newBuilder[String, NodeId]
      val scopes = Map.newBuilder[List[ScopePlace], NodeId]
      val parents = Map.newBuilder[NodeId, NodeId]

      // Recurses once for each scope nested in another, as registering them does.
      def add(children: List[ScopeOrTest], parent: NodeId, firstPosition: Int, pathOfParent: List[ScopePlace]): Unit = {
        children.zipWithIndex.foldLeft(Map.empty[String, Int]) {
          case (scopesSoFarByText, (ScopeOrTest.Test(name, testName, label), index)) =>
            val id = mint()
            nodes += StructureNode.Test(id, parent, firstPosition + index, name, label)
            tests += testName -> id
            parents += id -> parent
            scopesSoFarByText
          case (scopesSoFarByText, (ScopeOrTest.Scope(name, text, label, scopesAndTests), index)) =>
            val id = mint()
            val place = scopesSoFarByText.getOrElse(text, 0)
            val path = pathOfParent :+ ScopePlace(text, place)
            nodes += StructureNode.Scope(id, parent, firstPosition + index, name, label)
            scopes += path -> id
            parents += id -> parent
            add(scopesAndTests, id, 0, path)
            scopesSoFarByText.updated(text, place + 1)
        }
      }
    }
  }
}
