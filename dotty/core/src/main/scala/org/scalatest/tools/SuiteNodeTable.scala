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
  def number(execution: Execution): Int = {
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

/** The node IDs of a suite's scopes and tests: each test's by test name, each scope's by its path, and each one's parent. */
private[scalatest] final case class ScopeAndTestIds(
  tests: Map[String, NodeId],
  scopes: Map[List[ScopePlace], NodeId],
  parents: Map[NodeId, NodeId]
)

private[scalatest] object ScopeAndTestIds {
  val none: ScopeAndTestIds = ScopeAndTestIds(Map.empty, Map.empty, Map.empty)
}

/**
 * The node IDs a run's test event log gives one suite node: the suite's own, and those of its tests, scopes and
 * nested suites. It also keeps the suite's latest structure, and the executions of the suite and of its scopes and
 * tests.
 *
 * Not a case class: the suite instance is user code, whose `equals`, `hashCode` and `toString` must not be called.
 *
 * @param instance the suite instance the suite's structure was read from
 * @param parent the suite node this one is nested in, where it is nested, and `position` its place among that node's children
 * @param firstScopesAndTests the scopes and tests of the suite's first structure
 * @param firstIds the node IDs of `firstScopesAndTests`
 * @param nestedSuites each nested suite's table, at its position in `nestedSuites`
 */
private[scalatest] final class SuiteNodeTable(
  val instance: Suite,
  val node: NodeId,
  val name: String,
  val suiteId: String,
  val className: String,
  val parent: Option[NodeId],
  val position: Option[Int],
  firstScopesAndTests: List[ScopeOrTest],
  firstIds: ScopeAndTestIds,
  val nestedSuites: Vector[SuiteNodeTable]
) {

  // All are read and written only under the emitter's lock. The IDs are those of every scope and test any structure
  // of the suite has held, so that each keeps its ID in a later structure.
  private val executionsOfNodes = mutable.HashMap.empty[NodeId, NodeExecutions]
  private var openedBySuiteStarting = Option.empty[Execution]
  private var ids = firstIds
  private var latestScopesAndTests = firstScopesAndTests

  /** Each test's node, by test name. */
  def tests: Map[String, NodeId] = ids.tests

  /** Each scope's node, by its path: the place of each scope from the suite down to it. */
  def scopes: Map[List[ScopePlace], NodeId] = ids.scopes

  /** The parent of each scope and test. */
  def parents: Map[NodeId, NodeId] = ids.parents

  /** The scopes and tests of the suite's latest structure. */
  def scopesAndTests: List[ScopeOrTest] = latestScopesAndTests

  /**
   * Makes `scopesAndTests` the suite's latest structure, minting a node for each scope and test no earlier structure
   * of the suite held, and returns the nodes of the further `structure` event that holds them: the suite, then its
   * scopes and tests, positioned from 0 among themselves.
   */
  def furtherStructure(scopesAndTests: List[ScopeOrTest], mint: () => NodeId): Vector[StructureNode] = {
    val nodes = Vector.newBuilder[StructureNode]
    nodes += StructureNode.Suite(node, parent, position, name, suiteId, className)
    val added = new SuiteNodeTable.ScopesAndTests(ids, mint, nodes)
    added.add(scopesAndTests, node, 0, Nil)
    ids = added.ids
    latestScopesAndTests = scopesAndTests
    nodes.result()
  }

  /** The node of a scope or test, or none for the suite itself and for a scope or test no structure of the suite holds. */
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
  def forgetUnrunExecution(execution: Execution): Unit = openedBySuiteStarting = openedBySuiteStarting.filterNot(_ eq execution)

  /** The execution of a run that starts: the one the suite's `SuiteStarting` opened where no run has taken it, and otherwise a new one. */
  def takeExecutionForStartingRun(): Execution = {
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
      val scopesAndTests = new ScopesAndTests(ScopeAndTestIds.none, mint, nodes)
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
        structure.name,
        structure.suiteId,
        structure.className,
        parent,
        position,
        structure.scopesAndTests,
        scopesAndTests.ids,
        nestedSuites
      )
    }
  }

  /** Adds scopes and tests to a structure's nodes, each taking the ID `known` holds for it, where it holds one, and a minted one otherwise. */
  private final class ScopesAndTests(known: ScopeAndTestIds, mint: () => NodeId, nodes: mutable.Builder[StructureNode, Vector[StructureNode]]) {

    private var added = known

    /** The IDs `known` holds, with those of the scopes and tests added, each under the parent it was added under. */
    def ids: ScopeAndTestIds = added

    // Recurses once for each scope nested in another, as registering them does.
    def add(children: List[ScopeOrTest], parent: NodeId, firstPosition: Int, pathOfParent: List[ScopePlace]): Unit = {
      children.zipWithIndex.foldLeft(Map.empty[String, Int]) {
        case (scopesSoFarByText, (ScopeOrTest.Test(name, testName, label), index)) =>
          val id = added.tests.getOrElse(testName, mint())
          nodes += StructureNode.Test(id, parent, firstPosition + index, name, label)
          added = added.copy(tests = added.tests.updated(testName, id), parents = added.parents.updated(id, parent))
          scopesSoFarByText
        case (scopesSoFarByText, (ScopeOrTest.Scope(name, text, label, scopesAndTests), index)) =>
          val place = scopesSoFarByText.getOrElse(text, 0)
          val path = pathOfParent :+ ScopePlace(text, place)
          val id = added.scopes.getOrElse(path, mint())
          nodes += StructureNode.Scope(id, parent, firstPosition + index, name, label)
          added = added.copy(scopes = added.scopes.updated(path, id), parents = added.parents.updated(id, parent))
          add(scopesAndTests, id, 0, path)
          scopesSoFarByText.updated(text, place + 1)
      }
    }
  }
}
