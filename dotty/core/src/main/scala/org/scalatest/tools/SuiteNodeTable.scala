package org.scalatest.tools

import java.util.IdentityHashMap
import org.scalatest.ScopePlace
import org.scalatest.events.NodeId
import org.scalatest.events.StructureNode
import org.scalatest.events.SuiteId
import org.scalatest.events.TestName
import scala.collection.mutable

private[scalatest] final class Execution(emitter: TestEventLogEmitter) {

  private val suitesStartedBySuiteId = new GuardedByEmitter(emitter, mutable.HashMap.empty[SuiteId, Int])

  def indexOfStartingSuite(suiteId: SuiteId): Int = {
    val started = suitesStartedBySuiteId.get
    val index = started.getOrElse(suiteId, 0)
    started(suiteId) = index + 1
    index
  }
}

private[scalatest] final class NodeExecutions(emitter: TestEventLogEmitter) {

  private val indexes = new GuardedByEmitter(emitter, new IdentityHashMap[Execution, Integer])
  private val open = new GuardedByEmitter(emitter, List.empty[Execution])
  private val lastIndexedExecution = new GuardedByEmitter(emitter, Option.empty[Execution])

  def indexOf(execution: Execution): Int = {
    val indexed = indexes.get
    Option(indexed.get(execution)) match {
      case Some(index) => index
      case None =>
        val index = indexed.size + 1
        indexed.put(execution, index)
        open.set(execution :: open.get)
        lastIndexedExecution.set(Some(execution))
        index
    }
  }

  def close(execution: Execution): Unit = open.set(open.get.filterNot(_ eq execution))

  def latestOpen: Option[Execution] = open.get.headOption

  def lastIndexed: Option[Execution] = lastIndexedExecution.get

  def newExecution(): Execution = new Execution(emitter)

  def indexOfCurrent: Int = indexOf(latestOpen.orElse(lastIndexed).getOrElse(newExecution()))
}

private[scalatest] final case class NamedExecutions(
  ofNodeAbove: Option[NamedExecutions],
  ofSuite: Execution,
  ofScopes: Map[List[ScopePlace], Execution],
  ofOwnNode: Option[Execution]
)

private[scalatest] final case class ScopeAndTestIds(
  testsByName: Map[TestName, NodeId],
  scopesByPath: Map[List[ScopePlace], NodeId],
  parents: Map[NodeId, NodeId]
)

private[scalatest] object ScopeAndTestIds {
  val none: ScopeAndTestIds = ScopeAndTestIds(Map.empty, Map.empty, Map.empty)
}

private[scalatest] final class SuiteNodeTable(
  emitter: TestEventLogEmitter,
  val node: NodeId,
  val name: String,
  val suiteId: SuiteId,
  val className: String,
  val parentSuite: Option[SuiteNodeTable],
  val parent: Option[NodeId],
  val position: Option[Int],
  firstPositionOfScopesAndTests: Int,
  firstScopesAndTests: List[ScopeOrTest],
  firstIds: ScopeAndTestIds,
  firstFilteredTests: Map[TestName, FilterVerdict]
) {

  private val executionsOfNodes = new GuardedByEmitter(emitter, mutable.HashMap.empty[NodeId, NodeExecutions])
  private val openedBySuiteStarting = new GuardedByEmitter(emitter, Option.empty[Execution])
  private val ids = new GuardedByEmitter(emitter, firstIds)
  private val scopesAndTestsOfLatest = new GuardedByEmitter(emitter, firstScopesAndTests)
  private val testNamesOfLatest = new GuardedByEmitter(emitter, SuiteNodeTable.testNamesIn(firstScopesAndTests))
  private val filteredTests = new GuardedByEmitter(emitter, firstFilteredTests)
  private val nestedSuitesAdded = new GuardedByEmitter(emitter, Vector.empty[SuiteNodeTable])
  private val hasEndedOnce = new GuardedByEmitter(emitter, false)

  def nestedSuites: Vector[SuiteNodeTable] = heldNestedSuites

  // Set by the companion after this table is built, so that each nested table can take it as its parent. Never written
  // once the table is shared, so read without the emitter's lock.
  private var heldNestedSuites = Vector.empty[SuiteNodeTable]

  private def holdNestedSuites(tables: Vector[SuiteNodeTable]): Unit = heldNestedSuites = tables

  def childSuites: Vector[SuiteNodeTable] = nestedSuites ++ nestedSuitesAdded.get

  def hasEnded: Boolean = hasEndedOnce.get

  def markEnded(): Unit = hasEndedOnce.set(true)

  def addSuiteUnder(parentNode: NodeId, name: String, suiteId: SuiteId, className: String): SuiteNodeTable = {
    val id = SuiteNodeTable.nestedSuitePath(parentNode, suiteId, childSuites.filter(_.parent.contains(parentNode)).map(_.suiteId))
    addSuite(id, parentNode, name, suiteId, className)
  }

  def addSuite(id: NodeId, parentNode: NodeId, name: String, suiteId: SuiteId, className: String): SuiteNodeTable = {
    val added = new SuiteNodeTable(emitter, id, name, suiteId, className, Some(this), Some(parentNode), None, 0, Nil, ScopeAndTestIds.none, Map.empty)
    nestedSuitesAdded.set(nestedSuitesAdded.get :+ added)
    added
  }

  def testsByName: Map[TestName, NodeId] = ids.get.testsByName

  def scopesByPath: Map[List[ScopePlace], NodeId] = ids.get.scopesByPath

  def parents: Map[NodeId, NodeId] = ids.get.parents

  def latestScopesAndTests: List[ScopeOrTest] = scopesAndTestsOfLatest.get

  def latestHoldsTest(testName: TestName): Boolean = testNamesOfLatest.get.contains(testName)

  def filterKeepingEarlier(testNames: List[TestName], verdictOn: TestName => FilterVerdict): Map[TestName, FilterVerdict] = {
    // An earlier verdict stands: the first structure was filtered with the suite's own `tags`, which are not read again
    // for a further structure.
    val filteredNow = testNames.map(testName => testName -> filteredTests.get.getOrElse(testName, verdictOn(testName))).toMap
    filteredTests.set(filteredTests.get ++ filteredNow)
    filteredNow
  }

  def replaceLatestStructure(scopesAndTests: List[ScopeOrTest]): Vector[StructureNode] = {
    val nodes = Vector.newBuilder[StructureNode]
    nodes += StructureNode.Suite(node, parent, position, name, suiteId, className)
    val added = new SuiteNodeTable.ScopesAndTests(ids.get, node, nodes)
    // Numbered from where the first structure put its first scope or test, so that the scopes and tests keep their
    // place beside the nested suites a further structure leaves out.
    added.add(scopesAndTests, node, firstPositionOfScopesAndTests, Nil)
    ids.set(added.ids)
    scopesAndTestsOfLatest.set(scopesAndTests)
    testNamesOfLatest.set(SuiteNodeTable.testNamesIn(scopesAndTests))
    nodes.result()
  }

  def scopeOrTest(treeNode: TreeNode): Option[NodeId] = {
    emitter.assertLockHeld()
    treeNode match {
      case TreeNode.OfSuite => None
      case TreeNode.Scope(places) => scopesByPath.get(places)
      case TreeNode.Test(testName) => testsByName.get(testName)
    }
  }

  def executionsOf(node: NodeId): NodeExecutions = executionsOfNodes.get.getOrElseUpdate(node, new NodeExecutions(emitter))

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

  def executionPathAbove(scopeOrTest: NodeId, named: Option[NamedExecutions]): Vector[Int] = {
    def scopeAbove(below: NodeId): Option[NodeId] = parents.get(below).filter(_ != node)
    val scopesAbove = Iterator.unfold(scopeAbove(scopeOrTest))(scope => scope.map(id => (id, scopeAbove(id)))).toVector.reverse
    val namedScopes = named.fold(Map.empty[NodeId, Execution])(_.ofScopes.flatMap((path, execution) => scopesByPath.get(path).map(_ -> execution)))
    (executionPathAboveSuite(named) :+ executionIndexOf(node, named.map(_.ofSuite))) ++ scopesAbove.map(scope => executionIndexOf(scope, namedScopes.get(scope)))
  }

  private def executionIndexOf(node: NodeId, named: Option[Execution]): Int = {
    val executions = executionsOf(node)
    named.fold(executions.indexOfCurrent)(executions.indexOf)
  }

  def keepForNextRun(execution: Execution): Unit = openedBySuiteStarting.set(Some(execution))

  def forgetUnrunExecution(execution: Execution): Unit = openedBySuiteStarting.set(openedBySuiteStarting.get.filterNot(_ eq execution))

  def takeExecutionForStartingRun(): Execution = {
    val execution = openedBySuiteStarting.get.getOrElse(new Execution(emitter))
    openedBySuiteStarting.set(None)
    execution
  }
}

private[scalatest] object SuiteNodeTable {

  private def testNamesIn(scopesAndTests: List[ScopeOrTest]): Set[TestName] = SuiteStructure.testsIn(scopesAndTests).map(_.scalaTestName).toSet

  private def nestedSuitePath(parent: NodeId, suiteId: SuiteId, suiteIdsBefore: Vector[SuiteId]): NodeId =
    NodeId.nestedSuite(parent, suiteId, suiteIdsBefore.count(_ == suiteId))

  def ofTopLevelSuite(emitter: TestEventLogEmitter, structure: SuiteStructure, index: Int): (SuiteNodeTable, Vector[StructureNode]) = {
    val nodes = Vector.newBuilder[StructureNode]
    val table = suite(emitter, structure, NodeId.topLevelSuite(structure.suiteId, index), None, None, nodes)
    (table, nodes.result())
  }

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
      // Recurses no deeper than reading the structure did.
      suite(emitter, nestedSuite, nestedId, Some(table), Some(firstPositionOfNestedSuites + offset), nodes)
    })
    if (!structure.testsBeforeNestedSuites) {
      nodes ++= nodesOfScopesAndTests.result()
    }
    table
  }

  private final class ScopesAndTests(known: ScopeAndTestIds, suite: NodeId, nodes: mutable.Builder[StructureNode, Vector[StructureNode]]) {

    private var added = known

    def ids: ScopeAndTestIds = added

    def add(children: List[ScopeOrTest], parent: NodeId, firstPosition: Int, pathOfParent: List[ScopePlace]): Unit = {
      children.zipWithIndex.foldLeft(Map.empty[String, Int]) {
        case (scopesSoFarByText, (ScopeOrTest.Test(name, testName, label, ignored), offset)) =>
          val id = NodeId.test(suite, testName)
          nodes += StructureNode.Test(id, parent, firstPosition + offset, name, label, ignored)
          added = added.copy(testsByName = added.testsByName.updated(testName, id), parents = added.parents.updated(id, parent))
          scopesSoFarByText
        case (scopesSoFarByText, (ScopeOrTest.Scope(name, text, label, scopesAndTests), offset)) =>
          val index = scopesSoFarByText.getOrElse(text, 0)
          val id = NodeId.scope(parent, text, index)
          val path = pathOfParent :+ ScopePlace(text, index)
          nodes += StructureNode.Scope(id, parent, firstPosition + offset, name, label)
          added = added.copy(scopesByPath = added.scopesByPath.updated(path, id), parents = added.parents.updated(id, parent))
          // Recurses no deeper than registering the scopes did.
          add(scopesAndTests, id, 0, path)
          scopesSoFarByText.updated(text, index + 1)
      }
    }
  }
}
