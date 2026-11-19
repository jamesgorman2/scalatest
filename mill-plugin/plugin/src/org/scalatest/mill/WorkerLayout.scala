package org.scalatest.mill

import java.util.Locale
import org.scalatest.consolidation.ForkGroup
import org.scalatest.consolidation.TestRunLabel
import org.scalatest.consolidation.Worker
import org.scalatest.consolidation.WorkerIndex

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
 * @param forkGroupAndIndex the worker's fork group and its index within that group, which label the worker's run
 * @param processFolder the worker's `processFolder`, as path segments under the module's folder: its fork group's
 *                      folder, or its own folder `worker-<i>` in that folder where Mill runs the group's workers in
 *                      parallel
 */
private[mill] final case class PlannedWorker(forkGroupAndIndex: Worker, processFolder: Seq[String]) {

  def describedIn(module: TestRunLabel): String =
    s"worker ${forkGroupAndIndex.index.value} of fork group ${forkGroupAndIndex.forkGroup.position} of ${module.value}"
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
   * @param filesystemIgnoresCase whether the filesystem holding the `processFolder`s is case-insensitive, or why that is
   *                              unknown. Asked only where two or more groups hold a discovered class.
   * @return the layout, or why it cannot run: two fork groups would share a folder (Mill's `testForked` fails there too,
   *         as a `processFolder`'s second worker cannot start), or `filesystemIgnoresCase` gave no answer.
   */
  def ofQueue(
      forkGroups: Seq[Seq[String]],
      discovered: Set[String],
      testParallelism: Boolean,
      jobs: Int,
      filesystemIgnoresCase: () => Either[String, Boolean]
  ): Either[String, WorkerLayout] = {
    val groupsToRun = forkGroups.map(_.filter(discovered)).zipWithIndex.filter { case (classes, _) => classes.nonEmpty }
    val testClassCount = groupsToRun.map { case (classes, _) => classes.size }.sum
    val workersInFolders = testParallelism && testClassCount != 1
    def forkGroupOf(classes: Seq[String], position: Int, label: String, folder: Seq[String]): PlannedForkGroup = {
      val forkGroup = valid(ForkGroup.from(position))
      val workerCount = if (workersInFolders) Math.max(Math.min(jobs, classes.size), 1) else 1
      val workerIndexLength = workerCount.toString.length
      val workers = (0 until workerCount).map { index =>
        val processFolder = if (workersInFolders) folder :+ s"worker-${padded(index, workerIndexLength)}" else folder
        PlannedWorker(Worker(forkGroup, valid(WorkerIndex.from(index))), processFolder)
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
        filesystemIgnoresCase().flatMap(ignoresCase => sharedFolders(queued, ignoresCase).toLeft(WorkerLayout(testClassCount, queued)))
    }
  }

  /**
   * The layout for a module that sets `testBatchFrameworkTasks`: one fork group for each group holding a class, or fork
   * group 0 with no class where none does, as [[ofQueue]] gives them. Each group gets one worker, a batch worker,
   * with index 0 in the group's folder. The worker is given the group's whole class list, whatever the module's test
   * parallelism.
   *
   * @return the layout, or why it cannot run, as [[ofQueue]] gives them
   */
  def ofBatches(forkGroups: Seq[Seq[String]], filesystemIgnoresCase: () => Either[String, Boolean]): Either[String, WorkerLayout] =
    ofQueue(forkGroups, forkGroups.flatten.toSet, testParallelism = false, jobs = 1, filesystemIgnoresCase)

  private def padded(index: Int, length: Int): String = s"%0${length}d".format(index)

  private def groupFolderName(classes: Seq[String], label: String): String =
    classes match {
      case Seq(single) => single
      case multiple => s"group-$label-${multiple.head}"
    }

  private def sharedFolders(forkGroups: Seq[PlannedForkGroup], filesystemIgnoresCase: Boolean): Option[String] = {
    val folderOnDisk: String => String = if (filesystemIgnoresCase) _.toLowerCase(Locale.ROOT) else identity
    val shared = forkGroups
      .groupBy(_.folder.map(folderOnDisk))
      .values
      .filter(_.size > 1)
      .toSeq
      .sortBy(_.head.forkGroup.position)
      .map(describeSharing)
    Option.when(shared.nonEmpty)(shared.mkString("; "))
  }

  private def describeSharing(sharing: Seq[PlannedForkGroup]): String = {
    val positions = sharing.map(_.forkGroup.position)
    val processFolder = sharing.head.workers.head.processFolder.mkString("/")
    s"fork groups ${positions.init.mkString(", ")} and ${positions.last} share the processFolder $processFolder"
  }

  // Positions and indexes counted from 0 are never negative.
  private def valid[A](counted: Either[String, A]): A = counted.fold(reason => throw new IllegalStateException(reason), identity)
}
