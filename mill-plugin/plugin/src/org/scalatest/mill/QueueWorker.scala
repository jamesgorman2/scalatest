package org.scalatest.mill

import java.util.Locale
import org.scalatest.consolidation.ForkGroup
import org.scalatest.consolidation.TestRunLabel
import org.scalatest.consolidation.Worker
import org.scalatest.consolidation.WorkerIndex

/**
 * A worker the test command starts through Mill's test queue, one for each fork group holding a test class, as Mill
 * 1.3.1's `testForked` starts with test parallelism off.
 *
 * @param forkGroupAndIndex the worker's fork group and its index within that group, which label the worker's run
 * @param processFolder the worker's `processFolder`, as path segments under the module's folder: the folder Mill's
 *                      `testForked` would give the worker, under its own `Task.dest`
 * @param testClasses the classes of its fork group's queue
 */
private[mill] final case class QueueWorker(forkGroupAndIndex: Worker, processFolder: Seq[String], testClasses: Seq[String]) {

  def describedIn(module: TestRunLabel): String =
    s"worker ${forkGroupAndIndex.index.value} of fork group ${forkGroupAndIndex.forkGroup.position} of ${module.value}"
}

private[mill] object QueueWorker {

  /**
   * One worker for each fork group holding a discovered class. A fork group keeps its position among every group
   * `forkGroups` gives, so a group with no discovered class gets no worker but still counts. Where no group holds a
   * discovered class, one worker of fork group 0 is given no class, as Mill starts one to run the framework's setup and
   * teardown.
   *
   * @param filesystemIgnoresCase whether the filesystem holding the `processFolder`s is case-insensitive, or why that is
   *                              unknown. Asked only where two or more groups hold a discovered class.
   * @return the workers, or why they cannot run: two would share a `processFolder` (Mill's `testForked` fails there too,
   *         as a folder's second worker cannot start), or `filesystemIgnoresCase` gave no answer.
   */
  def forEachForkGroup(
      forkGroups: Seq[Seq[String]],
      discovered: Set[String],
      filesystemIgnoresCase: () => Either[String, Boolean]
  ): Either[String, Seq[QueueWorker]] = {
    val groupsToRun = forkGroups.map(_.filter(discovered)).zipWithIndex.filter { case (classes, _) => classes.nonEmpty }
    groupsToRun match {
      case Seq() => Right(Seq(QueueWorker(firstWorkerOf(0), Nil, Nil)))
      case Seq((classes, position)) => Right(Seq(QueueWorker(firstWorkerOf(position), Nil, classes)))
      case groups =>
        val folderIndexLength = groups.size.toString.length
        val workers = groups.zipWithIndex.map { case ((classes, position), folderIndex) =>
          QueueWorker(firstWorkerOf(position), Seq(groupFolderName(classes, folderIndex, folderIndexLength)), classes)
        }
        filesystemIgnoresCase().flatMap(ignoresCase => sharedProcessFolders(workers, ignoresCase).toLeft(workers))
    }
  }

  // Mill names a group's folder by the group's index among the groups it runs, not among every group.
  private def groupFolderName(classes: Seq[String], folderIndex: Int, folderIndexLength: Int): String =
    classes match {
      case Seq(single) => single
      case multiple => s"group-${s"%0${folderIndexLength}d".format(folderIndex)}-${multiple.head}"
    }

  private def sharedProcessFolders(workers: Seq[QueueWorker], filesystemIgnoresCase: Boolean): Option[String] = {
    val folderOnDisk: String => String = if (filesystemIgnoresCase) _.toLowerCase(Locale.ROOT) else identity
    val shared = workers
      .groupBy(_.processFolder.map(folderOnDisk))
      .values
      .filter(_.size > 1)
      .toSeq
      .sortBy(_.head.forkGroupAndIndex.forkGroup.position)
      .map(describeSharing)
    Option.when(shared.nonEmpty)(shared.mkString("; "))
  }

  private def describeSharing(sharing: Seq[QueueWorker]): String = {
    val positions = sharing.map(_.forkGroupAndIndex.forkGroup.position)
    s"fork groups ${positions.init.mkString(", ")} and ${positions.last} share the processFolder ${sharing.head.processFolder.mkString("/")}"
  }

  private def firstWorkerOf(position: Int): Worker =
    ForkGroup
      .from(position)
      .flatMap(forkGroup => WorkerIndex.from(0).map(index => Worker(forkGroup, index)))
      .fold(reason => throw new IllegalStateException(reason), identity)
}
