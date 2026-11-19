package org.scalatest.mill

import org.scalatest.consolidation.ForkGroup
import org.scalatest.consolidation.TestRunLabel
import org.scalatest.consolidation.Worker
import org.scalatest.consolidation.WorkerIndex

/**
 * A worker the test command starts through Mill's test queue, one for each fork group, as Mill 1.3.1's `testForked`
 * starts with test parallelism off.
 *
 * @param processFolder the worker's `processFolder`, as path segments under the module's folder: the folder Mill's
 *                      `testForked` would give the worker, under its own `Task.dest`
 * @param testClasses the classes of its fork group's queue
 */
private[mill] final case class QueueWorker(worker: Worker, processFolder: Seq[String], testClasses: Seq[String]) {

  def describedIn(module: TestRunLabel): String =
    s"worker ${worker.index.value} of fork group ${worker.forkGroup.position} of ${module.value}"
}

private[mill] object QueueWorker {

  /**
   * One worker for each fork group holding a discovered class. A fork group keeps its position among every group
   * `forkGroups` gives, so a group with no discovered class gets no worker but still counts. Where no group holds a
   * discovered class, one worker of fork group 0 is given no class, as Mill starts one to run the framework's setup and
   * teardown.
   */
  def forEachForkGroup(forkGroups: Seq[Seq[String]], discovered: Set[String]): Seq[QueueWorker] = {
    val groupsToRun = forkGroups.map(_.filter(discovered)).zipWithIndex.filter { case (classes, _) => classes.nonEmpty }
    groupsToRun match {
      case Seq() => Seq(QueueWorker(firstWorkerOf(0), Nil, Nil))
      case Seq((classes, position)) => Seq(QueueWorker(firstWorkerOf(position), Nil, classes))
      case groups =>
        val folderIndexLength = groups.size.toString.length
        groups.zipWithIndex.map { case ((classes, position), folderIndex) =>
          QueueWorker(firstWorkerOf(position), Seq(groupFolderName(classes, folderIndex, folderIndexLength)), classes)
        }
    }
  }

  // Mill names a group's folder by the group's index among the groups it runs, not among every group.
  private def groupFolderName(classes: Seq[String], folderIndex: Int, folderIndexLength: Int): String =
    classes match {
      case Seq(single) => single
      case multiple => s"group-${s"%0${folderIndexLength}d".format(folderIndex)}-${multiple.head}"
    }

  private def firstWorkerOf(position: Int): Worker =
    ForkGroup
      .from(position)
      .flatMap(forkGroup => WorkerIndex.from(0).map(index => Worker(forkGroup, index)))
      .fold(reason => throw new IllegalStateException(reason), identity)
}
