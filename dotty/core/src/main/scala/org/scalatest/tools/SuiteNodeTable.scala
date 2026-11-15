package org.scalatest.tools

import java.util.IdentityHashMap
import org.scalatest.ScopePlace
import org.scalatest.events.NodeId
import org.scalatest.events.NodePlace
import org.scalatest.events.StructureNode
import scala.collection.mutable

/**
 * One execution of a suite, scope or test. It has no number until an event naming it is written. It counts, by suite
 * ID, the suites started in it.
 *
 * Read and written only under the emitter's lock.
 */
private[scalatest] final class Execution {

  private val startedOfSuiteId = mutable.HashMap.empty[String, Int]

  /** The index of a suite of this suite ID starting in this execution among those started in it before, which it then counts. */
  def indexOfStartingSuite(suiteId: String): Int = {
    val index = startedOfSuiteId.getOrElse(suiteId, 0)
    startedOfSuiteId(suiteId) = index + 1
    index
  }
}

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

  /**
   * The number of the execution an event below this node ran in, where nothing names it: the execution that opened
   * last and has not closed, else the latest, else a new first one.
   */
  def numberOfCurrent: Int = number(openedLast.orElse(latest).getOrElse(new Execution))
}

/**
 * The executions an event's origin names:
 *  - those named for the node its suite sits under, a suite or a test, where its suite has a parent;
 *  - that of its suite;
 *  - that of each scope it ran in, by the scope's path;
 *  - that of its own node, where that is a scope or test.
 */
private[scalatest] final case class NamedExecutions(
  ofNodeAbove: Option[NamedExecutions],
  ofSuite: Execution,
  ofScopes: Map[List[ScopePlace], Execution],
  ofOwnNode: Option[Execution]
)

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
 * nested suites. Each ID is the node's node path:
 *  - a nested suite's is its parent's, then its `suiteId` and its index among its parent's suites of that `suiteId`;
 *  - a scope's is its parent's, then its registered text and its index among its parent's scopes of that text;
 *  - a test's is its suite's, then its test name.
 *
 * It also keeps the suite's latest structure, and the executions of the suite and of its scopes and tests.
 *
 * @param parentSuite the table of the suite node this one sits under, if any
 * @param parent the node it sits under, if any: its parent suite's node, or one of that suite's tests
 * @param position its place among that node's children, if it has one: a nested suite node added after its parent's first structure has none
 * @param firstScopesAndTests the scopes and tests of the suite's first structure
 * @param firstIds the node IDs of `firstScopesAndTests`
 * @param firstFilteredTests what the filter said of each test of the tree the first structure was read from, as `SuiteStructure.filteredTests` holds it
 */
private[scalatest] final class SuiteNodeTable(
  val node: NodeId,
  val name: String,
  val suiteId: String,
  val className: String,
  val parentSuite: Option[SuiteNodeTable],
  val parent: Option[NodeId],
  val position: Option[Int],
  firstScopesAndTests: List[ScopeOrTest],
  firstIds: ScopeAndTestIds,
  firstFilteredTests: Map[String, (Boolean, Boolean)]
) {

  // All are read and written only under the emitter's lock. The IDs are those of every scope and test any structure
  // of the suite has held, so that each keeps its ID in a later structure.
  private val executionsOfNodes = mutable.HashMap.empty[NodeId, NodeExecutions]
  private var openedBySuiteStarting = Option.empty[Execution]
  private var ids = firstIds
  private var latestScopesAndTests = firstScopesAndTests
  private var testNamesOfLatest = SuiteNodeTable.testNamesIn(firstScopesAndTests)
  private var filteredTests = firstFilteredTests
  private var nestedSuitesAdded = Vector.empty[SuiteNodeTable]
  private var hasEndedOnce = false

  /** The tables of the nested suites of its first structure, each at its position among them. */
  def nestedSuites: Vector[SuiteNodeTable] = nestedSuitesOfFirstStructure

  // Set once, by the companion, after this table is built, so that each nested table's parent is a whole table. Set
  // before the table is shared, and never written again, so it is read without the emitter's lock.
  private var nestedSuitesOfFirstStructure = Vector.empty[SuiteNodeTable]

  private def holdNestedSuitesOfFirstStructure(tables: Vector[SuiteNodeTable]): Unit = nestedSuitesOfFirstStructure = tables

  /**
   * The tables of the suites under it, directly or under one of its tests: those of its first structure in declared
   * order, then those added later, in the order added.
   */
  def childSuites: Vector[SuiteNodeTable] = nestedSuites ++ nestedSuitesAdded

  /** Whether an event has ended an execution of the suite: a `SuiteCompleted` or `SuiteAborted`. */
  def hasEnded: Boolean = hasEndedOnce

  def markEnded(): Unit = hasEndedOnce = true

  /**
   * Adds a suite node nested directly in this one, after its other children. Its first structure holds the suite
   * alone, and is the caller's to write.
   */
  def addNestedSuite(name: String, suiteId: String, className: String): SuiteNodeTable = {
    val id = SuiteNodeTable.nestedSuiteId(node, suiteId, childSuites.filter(_.parent.contains(node)).map(_.suiteId))
    addSuite(id, node, name, suiteId, className)
  }

  /**
   * Adds a suite node of node path `id` under `parentNode`, which is this suite's node or that of one of its tests,
   * after the other suites under this one. Its first structure holds the suite alone, and is the caller's to write.
   */
  def addSuite(id: NodeId, parentNode: NodeId, name: String, suiteId: String, className: String): SuiteNodeTable = {
    val added = new SuiteNodeTable(id, name, suiteId, className, Some(this), Some(parentNode), None, Nil, ScopeAndTestIds.none, Map.empty)
    nestedSuitesAdded :+= added
    added
  }

  /** Each test's node, by test name. */
  def tests: Map[String, NodeId] = ids.tests

  /** Each scope's node, by its path: the place of each scope from the suite down to it. */
  def scopes: Map[List[ScopePlace], NodeId] = ids.scopes

  /** The parent of each scope and test. */
  def parents: Map[NodeId, NodeId] = ids.parents

  /** The scopes and tests of the suite's latest structure. */
  def scopesAndTests: List[ScopeOrTest] = latestScopesAndTests

  /** Whether the suite's latest structure holds a test of this test name. */
  def latestHoldsTest(testName: String): Boolean = testNamesOfLatest.contains(testName)

  /**
   * What the filter says of each test of `testNames`, as `Filter.apply` says it for one test. A test an earlier
   * structure of the suite filtered keeps that result, since the first structure is filtered by the suite's own
   * `tags`, which a further structure cannot call. Any other test is filtered by `filtered`, and the table keeps the
   * result for later structures.
   */
  def filterKeepingEarlier(testNames: List[String], filtered: String => (Boolean, Boolean)): Map[String, (Boolean, Boolean)] = {
    val filteredNow = testNames.map(testName => testName -> filteredTests.getOrElse(testName, filtered(testName))).toMap
    filteredTests = filteredTests ++ filteredNow
    filteredNow
  }

  /**
   * Makes `scopesAndTests` the suite's latest structure. Returns the nodes of the further `structure` event that holds
   * them: the suite, then its scopes and tests, positioned from 0 among themselves.
   */
  def replaceLatestStructure(scopesAndTests: List[ScopeOrTest]): Vector[StructureNode] = {
    val nodes = Vector.newBuilder[StructureNode]
    nodes += StructureNode.Suite(node, parent, position, name, suiteId, className)
    val added = new SuiteNodeTable.ScopesAndTests(ids, node, nodes)
    added.add(scopesAndTests, node, 0, Nil)
    ids = added.ids
    latestScopesAndTests = scopesAndTests
    testNamesOfLatest = SuiteNodeTable.testNamesIn(scopesAndTests)
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

  /**
   * The execution path of an event of this suite, without the suite's own execution. Each node above it, from its
   * top-level suite down, takes the execution `named` gives it, and otherwise its current one. A suite that sits under
   * a test runs within that test's execution, and so within the executions of the scopes the test sits in.
   */
  def executionPathAboveSuite(named: Option[NamedExecutions]): Vector[Int] = {
    parentSuite.fold(Vector.empty[Int]) { above =>
      val namedAbove = named.flatMap(_.ofNodeAbove)
      parent.filter(_ != above.node) match {
        case Some(test) => above.executionPathAbove(test, namedAbove) :+ above.numberOf(test, namedAbove.flatMap(_.ofOwnNode))
        case None => above.executionPathAboveSuite(namedAbove) :+ above.numberOf(above.node, namedAbove.map(_.ofSuite))
      }
    }
  }

  /**
   * The execution path of an event of one of this suite's scopes or tests, without the node's own execution. The
   * suite and each scope between it and the node take the execution `named` gives them, and otherwise their current
   * one, as each node above the suite does. A test's scopes are those its latest structure puts it in.
   */
  def executionPathAbove(scopeOrTest: NodeId, named: Option[NamedExecutions]): Vector[Int] = {
    def scopeAbove(below: NodeId): Option[NodeId] = parents.get(below).filter(_ != node)
    val scopesAbove = Iterator.unfold(scopeAbove(scopeOrTest))(scope => scope.map(id => (id, scopeAbove(id)))).toVector.reverse
    val namedScopes = named.fold(Map.empty[NodeId, Execution])(_.ofScopes.flatMap((path, execution) => scopes.get(path).map(_ -> execution)))
    (executionPathAboveSuite(named) :+ numberOf(node, named.map(_.ofSuite))) ++ scopesAbove.map(scope => numberOf(scope, namedScopes.get(scope)))
  }

  // The number of a node's execution: the one named, else its current one.
  private def numberOf(node: NodeId, execution: Option[Execution]): Int = {
    val executions = executionsOf(node)
    execution.fold(executions.numberOfCurrent)(executions.number)
  }

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

  private def testNamesIn(scopesAndTests: List[ScopeOrTest]): Set[String] = SuiteStructure.testsIn(scopesAndTests).map(_.testName).toSet

  // The node path of a suite of this suite ID nested in `parent`, after nested suites of the suite IDs `earlier`.
  private def nestedSuiteId(parent: NodeId, suiteId: String, earlier: Vector[String]): NodeId =
    NodeId(parent.places :+ NodePlace.Suite(suiteId, earlier.count(_ == suiteId)))

  /**
   * Gives a top-level suite, and each node of its structure, its node path. The suite's is its `suiteId` and `index`,
   * its index among the run's top-level suites of that `suiteId`. Returns the suite's table, and the nodes as a
   * `structure` event holds them: in depth-first pre-order, each node's children in declared order.
   */
  def ofTopLevelSuite(structure: SuiteStructure, index: Int): (SuiteNodeTable, Vector[StructureNode]) = {
    val nodes = Vector.newBuilder[StructureNode]
    val table = suite(structure, NodeId(Vector(NodePlace.Suite(structure.suiteId, index))), None, None, nodes)
    (table, nodes.result())
  }

  // Recurses once for each suite nested in another, as reading the structure does.
  private def suite(
    structure: SuiteStructure,
    id: NodeId,
    parentSuite: Option[SuiteNodeTable],
    position: Option[Int],
    nodes: mutable.Builder[StructureNode, Vector[StructureNode]]
  ): SuiteNodeTable = {
    nodes += StructureNode.Suite(id, parentSuite.map(_.node), position, structure.name, structure.suiteId, structure.className)
    val nodesOfScopesAndTests = Vector.newBuilder[StructureNode]
    val scopesAndTests = new ScopesAndTests(ScopeAndTestIds.none, id, nodesOfScopesAndTests)
    val (firstPositionOfScopesAndTests, firstPositionOfNestedSuites) =
      if (structure.testsBeforeNestedSuites) (0, structure.scopesAndTests.size) else (structure.nestedSuites.size, 0)
    scopesAndTests.add(structure.scopesAndTests, id, firstPositionOfScopesAndTests, Nil)
    if (structure.testsBeforeNestedSuites) {
      nodes ++= nodesOfScopesAndTests.result()
    }
    val table = new SuiteNodeTable(
      id,
      structure.name,
      structure.suiteId,
      structure.className,
      parentSuite,
      parentSuite.map(_.node),
      position,
      structure.scopesAndTests,
      scopesAndTests.ids,
      structure.filteredTests
    )
    val nested = structure.nestedSuites.toVector
    table.holdNestedSuitesOfFirstStructure(nested.zipWithIndex.map { (nestedSuite, index) =>
      val nestedId = nestedSuiteId(id, nestedSuite.suiteId, nested.take(index).map(_.suiteId))
      suite(nestedSuite, nestedId, Some(table), Some(firstPositionOfNestedSuites + index), nodes)
    })
    if (!structure.testsBeforeNestedSuites) {
      nodes ++= nodesOfScopesAndTests.result()
    }
    table
  }

  /** Adds scopes and tests to a structure's nodes, each taking its node path below the suite node `suite`. */
  private final class ScopesAndTests(known: ScopeAndTestIds, suite: NodeId, nodes: mutable.Builder[StructureNode, Vector[StructureNode]]) {

    private var added = known

    /** The IDs `known` holds, with those of the scopes and tests added, each under the parent it was added under. */
    def ids: ScopeAndTestIds = added

    // Recurses once for each scope nested in another, as registering them does.
    def add(children: List[ScopeOrTest], parent: NodeId, firstPosition: Int, pathOfParent: List[ScopePlace]): Unit = {
      children.zipWithIndex.foldLeft(Map.empty[String, Int]) {
        case (scopesSoFarByText, (ScopeOrTest.Test(name, testName, label), index)) =>
          val id = NodeId(suite.places :+ NodePlace.Test(testName))
          nodes += StructureNode.Test(id, parent, firstPosition + index, name, label)
          added = added.copy(tests = added.tests.updated(testName, id), parents = added.parents.updated(id, parent))
          scopesSoFarByText
        case (scopesSoFarByText, (ScopeOrTest.Scope(name, text, label, scopesAndTests), index)) =>
          val place = scopesSoFarByText.getOrElse(text, 0)
          val id = NodeId(parent.places :+ NodePlace.Scope(text, place))
          val path = pathOfParent :+ ScopePlace(text, place)
          nodes += StructureNode.Scope(id, parent, firstPosition + index, name, label)
          added = added.copy(scopes = added.scopes.updated(path, id), parents = added.parents.updated(id, parent))
          add(scopesAndTests, id, 0, path)
          scopesSoFarByText.updated(text, place + 1)
      }
    }
  }
}
