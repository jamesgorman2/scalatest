package org.scalatest.tools

import java.util.IdentityHashMap
import org.scalatest.ScopePlace
import org.scalatest.events.NodeId
import org.scalatest.events.NodePlace
import org.scalatest.events.StructureNode
import scala.collection.mutable

/**
 * One execution of a suite, scope or test.
 *
 * Read and written only under the emitter's lock.
 */
private[scalatest] final class Execution(emitter: TestEventLogEmitter) {

  private val suitesStartedBySuiteId = mutable.HashMap.empty[String, Int]

  /** Also counts the suite as started in this execution. */
  def indexOfStartingSuite(suiteId: String): Int = {
    emitter.assertLockHeld()
    val index = suitesStartedBySuiteId.getOrElse(suiteId, 0)
    suitesStartedBySuiteId(suiteId) = index + 1
    index
  }
}

/**
 * The executions of one node, indexed from 1 in the order the event log first names them.
 *
 * Read and written only under the emitter's lock.
 */
private[scalatest] final class NodeExecutions(emitter: TestEventLogEmitter) {

  private val indexes = new IdentityHashMap[Execution, Integer]
  private var open = List.empty[Execution]
  private var lastIndexedExecution = Option.empty[Execution]

  /** An execution not yet indexed takes the next index, becomes the node's last indexed execution and is marked open. */
  def indexOf(execution: Execution): Int = {
    emitter.assertLockHeld()
    Option(indexes.get(execution)) match {
      case Some(index) => index
      case None =>
        val index = indexes.size + 1
        indexes.put(execution, index)
        open = execution :: open
        lastIndexedExecution = Some(execution)
        index
    }
  }

  def close(execution: Execution): Unit = {
    emitter.assertLockHeld()
    open = open.filterNot(_ eq execution)
  }

  def latestOpen: Option[Execution] = {
    emitter.assertLockHeld()
    open.headOption
  }

  def lastIndexed: Option[Execution] = {
    emitter.assertLockHeld()
    lastIndexedExecution
  }

  def newExecution(): Execution = new Execution(emitter)

  /**
   * The execution an event below this node ran in, where no execution is named, is the first of:
   *  - the latest open execution;
   *  - the last indexed execution;
   *  - a new execution.
   */
  def indexOfCurrent: Int = {
    emitter.assertLockHeld()
    indexOf(latestOpen.orElse(lastIndexed).getOrElse(newExecution()))
  }
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

private[scalatest] final case class ScopeAndTestIds(
  testsByName: Map[String, NodeId],
  scopesByPath: Map[List[ScopePlace], NodeId],
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
 * @param emitter the emitter under whose lock the table is read and written once it is shared
 * @param parent the node it sits under, if any: its parent suite's node, or one of that suite's tests
 * @param position its place among that node's children, if it has one: a nested suite node added after its parent's first structure has none
 * @param firstPositionOfScopesAndTests the position every structure of the suite gives its first scope or test: after the
 *        nested suites its first structure placed before them
 * @param firstScopesAndTests the scopes and tests of the suite's first structure
 * @param firstFilteredTests what the filter said of each test of the tree the first structure was read from, as `SuiteStructure.filteredTests` holds it
 */
private[scalatest] final class SuiteNodeTable(
  emitter: TestEventLogEmitter,
  val node: NodeId,
  val name: String,
  val suiteId: String,
  val className: String,
  val parentSuite: Option[SuiteNodeTable],
  val parent: Option[NodeId],
  val position: Option[Int],
  firstPositionOfScopesAndTests: Int,
  firstScopesAndTests: List[ScopeOrTest],
  firstIds: ScopeAndTestIds,
  firstFilteredTests: Map[String, (Boolean, Boolean)]
) {

  // All are read and written only under the emitter's lock. The IDs are those of every scope and test any structure
  // of the suite has held, so that each keeps its ID in a later structure.
  private val executionsOfNodes = mutable.HashMap.empty[NodeId, NodeExecutions]
  private var openedBySuiteStarting = Option.empty[Execution]
  private var ids = firstIds
  private var scopesAndTestsOfLatest = firstScopesAndTests
  private var testNamesOfLatest = SuiteNodeTable.testNamesIn(firstScopesAndTests)
  private var filteredTests = firstFilteredTests
  private var nestedSuitesAdded = Vector.empty[SuiteNodeTable]
  private var hasEndedOnce = false

  def nestedSuites: Vector[SuiteNodeTable] = heldNestedSuites

  // Set once, by the companion, after this table is built, so that each nested table's parent is a whole table. Set
  // before the table is shared, and never written again, so it is read without the emitter's lock.
  private var heldNestedSuites = Vector.empty[SuiteNodeTable]

  private def holdNestedSuites(tables: Vector[SuiteNodeTable]): Unit = heldNestedSuites = tables

  /**
   * The tables of the suites under it, directly or under one of its tests: those of its first structure in declared
   * order, then those added later, in the order added.
   */
  def childSuites: Vector[SuiteNodeTable] = {
    emitter.assertLockHeld()
    nestedSuites ++ nestedSuitesAdded
  }

  def hasEnded: Boolean = {
    emitter.assertLockHeld()
    hasEndedOnce
  }

  def markEnded(): Unit = {
    emitter.assertLockHeld()
    hasEndedOnce = true
  }

  /**
   * Adds a suite node directly under `parentNode`, this suite's node or that of one of its tests, after the suites
   * already under it. Its first structure holds the suite alone, and is the caller's to write.
   */
  def addSuiteUnder(parentNode: NodeId, name: String, suiteId: String, className: String): SuiteNodeTable = {
    emitter.assertLockHeld()
    val id = SuiteNodeTable.nestedSuitePath(parentNode, suiteId, childSuites.filter(_.parent.contains(parentNode)).map(_.suiteId))
    addSuite(id, parentNode, name, suiteId, className)
  }

  /**
   * Adds a suite node of node path `id` under `parentNode`, which is this suite's node or that of one of its tests,
   * after the other suites under this one. Its first structure holds the suite alone, and is the caller's to write.
   */
  def addSuite(id: NodeId, parentNode: NodeId, name: String, suiteId: String, className: String): SuiteNodeTable = {
    emitter.assertLockHeld()
    val added = new SuiteNodeTable(emitter, id, name, suiteId, className, Some(this), Some(parentNode), None, 0, Nil, ScopeAndTestIds.none, Map.empty)
    nestedSuitesAdded :+= added
    added
  }

  def testsByName: Map[String, NodeId] = {
    emitter.assertLockHeld()
    ids.testsByName
  }

  /** By path: the place of each scope from the suite down to it. */
  def scopesByPath: Map[List[ScopePlace], NodeId] = {
    emitter.assertLockHeld()
    ids.scopesByPath
  }

  def parents: Map[NodeId, NodeId] = {
    emitter.assertLockHeld()
    ids.parents
  }

  def latestScopesAndTests: List[ScopeOrTest] = {
    emitter.assertLockHeld()
    scopesAndTestsOfLatest
  }

  def latestHoldsTest(testName: String): Boolean = {
    emitter.assertLockHeld()
    testNamesOfLatest.contains(testName)
  }

  /**
   * What the filter says of each test of `testNames`, as `Filter.apply` says it for one test. A test an earlier
   * structure of the suite filtered keeps that result, since the first structure is filtered by the suite's own
   * `tags`, which a further structure cannot call. Any other test is filtered by `filtered`, and the table keeps the
   * result for later structures.
   */
  def filterKeepingEarlier(testNames: List[String], filtered: String => (Boolean, Boolean)): Map[String, (Boolean, Boolean)] = {
    emitter.assertLockHeld()
    val filteredNow = testNames.map(testName => testName -> filteredTests.getOrElse(testName, filtered(testName))).toMap
    filteredTests = filteredTests ++ filteredNow
    filteredNow
  }

  /**
   * Makes `scopesAndTests` the suite's latest structure. Returns the nodes of the further `structure` event that holds
   * them: the suite, then its scopes and tests, positioned as the first structure positions them, so that a scope or
   * test keeps one numbering beside the nested suites the further structure leaves out.
   */
  def replaceLatestStructure(scopesAndTests: List[ScopeOrTest]): Vector[StructureNode] = {
    emitter.assertLockHeld()
    val nodes = Vector.newBuilder[StructureNode]
    nodes += StructureNode.Suite(node, parent, position, name, suiteId, className)
    val added = new SuiteNodeTable.ScopesAndTests(ids, node, nodes)
    added.add(scopesAndTests, node, firstPositionOfScopesAndTests, Nil)
    ids = added.ids
    scopesAndTestsOfLatest = scopesAndTests
    testNamesOfLatest = SuiteNodeTable.testNamesIn(scopesAndTests)
    nodes.result()
  }

  /** The node of a scope or test, or none for the suite itself and for a scope or test no structure of the suite holds. */
  def scopeOrTest(treeNode: TreeNode): Option[NodeId] = {
    emitter.assertLockHeld()
    treeNode match {
      case TreeNode.OfSuite => None
      case TreeNode.Scope(places) => scopesByPath.get(places)
      case TreeNode.Test(testName) => testsByName.get(testName)
    }
  }

  def executionsOf(node: NodeId): NodeExecutions = {
    emitter.assertLockHeld()
    executionsOfNodes.getOrElseUpdate(node, new NodeExecutions(emitter))
  }

  /**
   * The execution path of an event of this suite, without the suite's own execution. Each node above it, from its
   * top-level suite down, takes the execution `named` gives it, and otherwise its current one. A suite that sits under
   * a test runs within that test's execution, and so within the executions of the scopes the test sits in.
   */
  def executionPathAboveSuite(named: Option[NamedExecutions]): Vector[Int] = {
    emitter.assertLockHeld()
    parentSuite.fold(Vector.empty[Int]) { above =>
      val namedAbove = named.flatMap(_.ofNodeAbove)
      parent.filter(_ != above.node) match {
        case Some(test) => above.executionPathAbove(test, namedAbove) :+ above.executionIndexOf(test, namedAbove.flatMap(_.ofOwnNode))
        case None => above.executionPathAboveSuite(namedAbove) :+ above.executionIndexOf(above.node, namedAbove.map(_.ofSuite))
      }
    }
  }

  /**
   * The execution path of an event of one of this suite's scopes or tests, without the node's own execution. The
   * suite and each scope between it and the node take the execution `named` gives them, and otherwise their current
   * one, as each node above the suite does. A test's scopes are those its latest structure puts it in.
   */
  def executionPathAbove(scopeOrTest: NodeId, named: Option[NamedExecutions]): Vector[Int] = {
    emitter.assertLockHeld()
    def scopeAbove(below: NodeId): Option[NodeId] = parents.get(below).filter(_ != node)
    val scopesAbove = Iterator.unfold(scopeAbove(scopeOrTest))(scope => scope.map(id => (id, scopeAbove(id)))).toVector.reverse
    val namedScopes = named.fold(Map.empty[NodeId, Execution])(_.ofScopes.flatMap((path, execution) => scopesByPath.get(path).map(_ -> execution)))
    (executionPathAboveSuite(named) :+ executionIndexOf(node, named.map(_.ofSuite))) ++ scopesAbove.map(scope => executionIndexOf(scope, namedScopes.get(scope)))
  }

  private def executionIndexOf(node: NodeId, named: Option[Execution]): Int = {
    emitter.assertLockHeld()
    val executions = executionsOf(node)
    named.fold(executions.indexOfCurrent)(executions.indexOf)
  }

  /** Keeps the execution the suite's `SuiteStarting` opened for the suite's next run to take. */
  def keepForNextRun(execution: Execution): Unit = {
    emitter.assertLockHeld()
    openedBySuiteStarting = Some(execution)
  }

  /** Forgets the execution the suite's `SuiteStarting` opened, where that execution has closed with no run. */
  def forgetUnrunExecution(execution: Execution): Unit = {
    emitter.assertLockHeld()
    openedBySuiteStarting = openedBySuiteStarting.filterNot(_ eq execution)
  }

  /** The execution of a run that starts: the one the suite's `SuiteStarting` opened where no run has taken it, and otherwise a new one. */
  def takeExecutionForStartingRun(): Execution = {
    emitter.assertLockHeld()
    val execution = openedBySuiteStarting.getOrElse(new Execution(emitter))
    openedBySuiteStarting = None
    execution
  }
}

private[scalatest] object SuiteNodeTable {

  private def testNamesIn(scopesAndTests: List[ScopeOrTest]): Set[String] = SuiteStructure.testsIn(scopesAndTests).map(_.testName).toSet

  private def nestedSuitePath(parent: NodeId, suiteId: String, suiteIdsBefore: Vector[String]): NodeId =
    NodeId(parent.places :+ NodePlace.Suite(suiteId, suiteIdsBefore.count(_ == suiteId)))

  /**
   * Gives a top-level suite, and each node of its structure, its node path. The suite's is its `suiteId` and `index`,
   * its index among the run's top-level suites of that `suiteId`. Returns the suite's table, and the nodes as a
   * `structure` event holds them: in depth-first pre-order, each node's children in declared order.
   */
  def ofTopLevelSuite(emitter: TestEventLogEmitter, structure: SuiteStructure, index: Int): (SuiteNodeTable, Vector[StructureNode]) = {
    val nodes = Vector.newBuilder[StructureNode]
    val table = suite(emitter, structure, NodeId(Vector(NodePlace.Suite(structure.suiteId, index))), None, None, nodes)
    (table, nodes.result())
  }

  // Recurses once for each suite nested in another, as reading the structure does.
  private def suite(
    emitter: TestEventLogEmitter,
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
      emitter,
      id,
      structure.name,
      structure.suiteId,
      structure.className,
      parentSuite,
      parentSuite.map(_.node),
      position,
      firstPositionOfScopesAndTests,
      structure.scopesAndTests,
      scopesAndTests.ids,
      structure.filteredTests
    )
    val nested = structure.nestedSuites.toVector
    table.holdNestedSuites(nested.zipWithIndex.map { (nestedSuite, offset) =>
      val nestedId = nestedSuitePath(id, nestedSuite.suiteId, nested.take(offset).map(_.suiteId))
      suite(emitter, nestedSuite, nestedId, Some(table), Some(firstPositionOfNestedSuites + offset), nodes)
    })
    if (!structure.testsBeforeNestedSuites) {
      nodes ++= nodesOfScopesAndTests.result()
    }
    table
  }

  /** Adds scopes and tests to a structure's nodes, each taking its node path below the suite node `suite`. */
  private final class ScopesAndTests(known: ScopeAndTestIds, suite: NodeId, nodes: mutable.Builder[StructureNode, Vector[StructureNode]]) {

    private var added = known

    def ids: ScopeAndTestIds = added

    // Recurses once for each scope nested in another, as registering them does.
    def add(children: List[ScopeOrTest], parent: NodeId, firstPosition: Int, pathOfParent: List[ScopePlace]): Unit = {
      children.zipWithIndex.foldLeft(Map.empty[String, Int]) {
        case (scopesSoFarByText, (ScopeOrTest.Test(name, testName, label, ignored), offset)) =>
          val id = NodeId(suite.places :+ NodePlace.Test(testName))
          nodes += StructureNode.Test(id, parent, firstPosition + offset, name, label, ignored)
          added = added.copy(testsByName = added.testsByName.updated(testName, id), parents = added.parents.updated(id, parent))
          scopesSoFarByText
        case (scopesSoFarByText, (ScopeOrTest.Scope(name, text, label, scopesAndTests), offset)) =>
          val index = scopesSoFarByText.getOrElse(text, 0)
          val id = NodeId(parent.places :+ NodePlace.Scope(text, index))
          val path = pathOfParent :+ ScopePlace(text, index)
          nodes += StructureNode.Scope(id, parent, firstPosition + offset, name, label)
          added = added.copy(scopesByPath = added.scopesByPath.updated(path, id), parents = added.parents.updated(id, parent))
          add(scopesAndTests, id, 0, path)
          scopesSoFarByText.updated(text, index + 1)
      }
    }
  }
}
