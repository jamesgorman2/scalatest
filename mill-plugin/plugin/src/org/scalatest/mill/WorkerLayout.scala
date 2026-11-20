package org.scalatest.mill

import java.util.Locale
import org.scalatest.consolidation.ForkGroup
import org.scalatest.consolidation.TestRunLabel
import org.scalatest.consolidation.Worker
import org.scalatest.consolidation.WorkerIndex
import scala.util.control.NoStackTrace

/**
 * The fork groups and workers of a JVM test module that the test command starts: those Mill 1.3.1's `testForked` starts.
 *
 * @param testClassCount the number of test classes, summed over every fork group
 */
private[mill] final case class WorkerLayout(testClassCount: Int, forkGroups: Seq[PlannedForkGroup])

/**
 * A fork group holding a class to run, or the one fork group of a module holding none.
 *
 * @param label the group's index among the groups Mill runs, as Mill pads it, which keys the group's workers in Mill's
 *              logging
 * @param folder the group's folder, as path segments under the module's folder: the folder Mill's `testForked` would
 *               give the group, under its own `Task.dest`
 * @param testClasses the classes the group's workers are given: the classes of its queue, or its batch worker's class
 *                    list
 * @param workerIndexLength the number of digits each worker's index is padded to in its folder's name
 * @param workers the workers Mill starts for the group, in order of index
 */
private[mill] final case class PlannedForkGroup(
    forkGroup: ForkGroup,
    label: String,
    folder: Seq[String],
    testClasses: Seq[String],
    workerIndexLength: Int,
    workers: Seq[PlannedWorker]
) {

  def describedIn(module: TestRunLabel): String = s"fork group ${forkGroup.position} of ${module.value}"
}

/**
 * @param worker the fork group and index that identify the worker and label its run
 * @param processFolder the worker's `processFolder`, as path segments under the module's folder: its fork group's
 *                      folder, or its own folder `worker-<i>` in that folder where Mill runs the group's workers in
 *                      parallel
 */
private[mill] final case class PlannedWorker(worker: Worker, processFolder: Seq[String]) {

  def describedIn(module: TestRunLabel): String =
    s"worker ${worker.index.value} of fork group ${worker.forkGroup.position} of ${module.value}"
}

/**
 * Why a module's workers cannot be laid out: two or more fork groups would share a `processFolder`. Mill's `testForked`
 * fails there too, as a `processFolder`'s second worker cannot start.
 */
private[mill] final case class ForkGroupsShareProcessFolders(sharing: Seq[SharedProcessFolder]) extends Exception with NoStackTrace {

  override def getMessage: String = message

  def message: String = sharing.map(_.message).mkString("; ")
}

/**
 * @param processFolder the `processFolder` of the fork groups' first workers, as path segments under the module's folder
 */
private[mill] final case class SharedProcessFolder(forkGroups: Seq[ForkGroup], processFolder: Seq[String]) {

  def message: String = {
    val positions = forkGroups.map(_.position)
    s"fork groups ${positions.init.mkString(", ")} and ${positions.last} share the processFolder ${processFolder.mkString("/")}"
  }
}

private[mill] object WorkerLayout {

  /**
   * The layout Mill's test queue runs, for a module that does not set `testBatchFrameworkTasks`.
   *
   * One fork group for each group holding a discovered class. A fork group keeps its position among every group
   * `forkGroups` gives, so a group with no discovered class is not run but still counts. Where no group holds a
   * discovered class, fork group 0 is run with no class, as Mill runs it to run the framework's setup and teardown.
   *
   * Each group gets one worker. Where test parallelism is on, unless exactly one class is queued, it instead gets `jobs`
   * workers, capped at its class count and at least one, each in a folder of its own.
   *
   * @param filesystemIgnoresCase whether the filesystem holding the `processFolder`s is case-insensitive. Asked only where
   *                              two or more groups hold a discovered class; what it throws propagates.
   * @return the layout, or the fork groups that would share a `processFolder`
   */
  def ofQueue(
      forkGroups: Seq[Seq[String]],
      discovered: Set[String],
      testParallelism: Boolean,
      jobs: Int,
      filesystemIgnoresCase: () => Boolean
  ): Either[ForkGroupsShareProcessFolders, WorkerLayout] = {
    val discoveredForkGroups = forkGroups.map(_.filter(discovered))
    val workersPerGroup =
      if (testParallelism && discoveredForkGroups.map(_.size).sum != 1)
        WorkersPerGroup.UpToJobsInOwnFolders(jobs)
      else
        WorkersPerGroup.OneInGroupFolder
    of(discoveredForkGroups, workersPerGroup, filesystemIgnoresCase)
  }

  /**
   * The layout for a module that sets `testBatchFrameworkTasks`. As in [[ofQueue]], it has one fork group for each group
   * holding a class, or fork group 0 with no class where none does. Each group gets one batch worker, with index 0 in
   * the group's folder. The worker is given the group's whole class list, whatever the module's test parallelism.
   *
   * @return the layout, or the fork groups that would share a `processFolder`
   */
  def ofBatches(forkGroups: Seq[Seq[String]], filesystemIgnoresCase: () => Boolean): Either[ForkGroupsShareProcessFolders, WorkerLayout] =
    of(forkGroups, WorkersPerGroup.OneInGroupFolder, filesystemIgnoresCase)

  private enum WorkersPerGroup {
    case OneInGroupFolder
    case UpToJobsInOwnFolders(jobs: Int)

    def count(classes: Seq[String]): Int =
      this match {
        case OneInGroupFolder => 1
        case UpToJobsInOwnFolders(jobs) => Math.max(Math.min(jobs, classes.size), 1)
      }

    def processFolder(groupFolder: Seq[String], index: Int, indexLength: Int): Seq[String] =
      this match {
        case OneInGroupFolder => groupFolder
        case UpToJobsInOwnFolders(_) => groupFolder :+ s"worker-${padded(index, indexLength)}"
      }
  }

  private def of(
      forkGroups: Seq[Seq[String]],
      workersPerGroup: WorkersPerGroup,
      filesystemIgnoresCase: () => Boolean
  ): Either[ForkGroupsShareProcessFolders, WorkerLayout] = {
    val groupsToRun = forkGroups.zipWithIndex.filter { case (classes, _) => classes.nonEmpty }
    val testClassCount = groupsToRun.map { case (classes, _) => classes.size }.sum
    def forkGroupOf(classes: Seq[String], position: Int, label: String, folder: Seq[String]): PlannedForkGroup = {
      val forkGroup = ForkGroup.at(position)
      val workerCount = workersPerGroup.count(classes)
      val workerIndexLength = workerCount.toString.length
      val workers = (0 until workerCount).map { index =>
        PlannedWorker(Worker(forkGroup, WorkerIndex(index)), workersPerGroup.processFolder(folder, index, workerIndexLength))
      }
      PlannedForkGroup(forkGroup, label, folder, classes, workerIndexLength, workers)
    }
    groupsToRun match {
      case Seq() => Right(WorkerLayout(testClassCount, Seq(forkGroupOf(Nil, 0, "0", Nil))))
      case Seq((classes, position)) => Right(WorkerLayout(testClassCount, Seq(forkGroupOf(classes, position, "0", Nil))))
      case groups =>
        val labelLength = groups.size.toString.length
        val queued = groups.zipWithIndex.map { case ((classes, position), runIndex) =>
          val label = padded(runIndex, labelLength)
          forkGroupOf(classes, position, label, Seq(groupFolderName(classes, label)))
        }
        sharedProcessFolders(queued, filesystemIgnoresCase()).toLeft(WorkerLayout(testClassCount, queued))
    }
  }

  private def padded(index: Int, length: Int): String = s"%0${length}d".format(index)

  private def groupFolderName(classes: Seq[String], label: String): String =
    classes match {
      case Seq(single) => single
      case multiple => s"group-$label-${multiple.head}"
    }

  private def sharedProcessFolders(forkGroups: Seq[PlannedForkGroup], filesystemIgnoresCase: Boolean): Option[ForkGroupsShareProcessFolders] = {
    val folderOnDisk: String => String = if (filesystemIgnoresCase) _.toLowerCase(Locale.ROOT) else identity
    val sharing = forkGroups
      .groupBy(_.folder.map(folderOnDisk))
      .values
      .filter(_.size > 1)
      .toSeq
      .sortBy(_.head.forkGroup.position)
      .map(sharingGroups => SharedProcessFolder(sharingGroups.map(_.forkGroup), sharingGroups.head.workers.head.processFolder))
    Option.when(sharing.nonEmpty)(ForkGroupsShareProcessFolders(sharing))
  }
}
