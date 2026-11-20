package org.scalatest.mill

import java.util.Locale
import org.scalatest.consolidation.ForkGroup
import org.scalatest.consolidation.TestClassName
import org.scalatest.consolidation.TestRunLabel
import org.scalatest.consolidation.Worker
import org.scalatest.consolidation.WorkerIndex
import scala.util.control.NoStackTrace

private[mill] final case class WorkerLayout(forkGroups: Seq[PlannedForkGroup]) {

  def testClassCount: Int = forkGroups.map(_.testClasses.size).sum
}

private[mill] final case class PlannedForkGroup(
    forkGroup: ForkGroup,
    label: ForkGroupLabel,
    folder: PathUnderModule,
    testClasses: Seq[TestClassName],
    workers: Seq[PlannedWorker]
) {

  def workerIndexDigits: Int = PaddedIndex.digits(workers.size)

  def describedIn(module: TestRunLabel): String = s"fork group ${forkGroup.position} of ${module.value}"
}

private[mill] final case class PlannedWorker(worker: Worker, processFolder: PathUnderModule) {

  def describedIn(module: TestRunLabel): String =
    s"worker ${worker.index.value} of fork group ${worker.forkGroup.position} of ${module.value}"
}

private[mill] final case class ForkGroupsShareProcessFolders(sharing: Seq[SharedProcessFolder]) extends Exception with NoStackTrace {

  override def getMessage: String = message

  def message: String = sharing.map(_.message).mkString("; ")
}

private[mill] final case class SharedProcessFolder(forkGroups: Seq[ForkGroup], processFolder: PathUnderModule) {

  def message: String = {
    val positions = forkGroups.map(_.position)
    s"fork groups ${positions.init.mkString(", ")} and ${positions.last} share the processFolder ${processFolder.render}"
  }
}

private[mill] object WorkerLayout {

  def ofQueue(
      forkGroups: Seq[Seq[TestClassName]],
      discovered: Set[TestClassName],
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

  def ofBatches(forkGroups: Seq[Seq[TestClassName]], filesystemIgnoresCase: () => Boolean): Either[ForkGroupsShareProcessFolders, WorkerLayout] =
    of(forkGroups, WorkersPerGroup.OneInGroupFolder, filesystemIgnoresCase)

  private enum WorkersPerGroup {
    case OneInGroupFolder
    case UpToJobsInOwnFolders(jobs: Int)

    def count(classes: Seq[TestClassName]): Int =
      this match {
        case OneInGroupFolder => 1
        case UpToJobsInOwnFolders(jobs) => Math.max(Math.min(jobs, classes.size), 1)
      }

    def processFolder(groupFolder: PathUnderModule, index: Int, workerCount: Int): PathUnderModule =
      this match {
        case OneInGroupFolder => groupFolder
        case UpToJobsInOwnFolders(_) => groupFolder.resolve(s"worker-${PaddedIndex.of(index, workerCount)}")
      }
  }

  private def of(
      forkGroups: Seq[Seq[TestClassName]],
      workersPerGroup: WorkersPerGroup,
      filesystemIgnoresCase: () => Boolean
  ): Either[ForkGroupsShareProcessFolders, WorkerLayout] = {
    val groupsToRun = forkGroups.zipWithIndex.filter { case (classes, _) => classes.nonEmpty }
    def forkGroupOf(classes: Seq[TestClassName], position: Int, label: ForkGroupLabel, folder: PathUnderModule): PlannedForkGroup = {
      val forkGroup = ForkGroup.at(position)
      val workerCount = workersPerGroup.count(classes)
      val workers = (0 until workerCount).map { index =>
        PlannedWorker(Worker(forkGroup, WorkerIndex(index)), workersPerGroup.processFolder(folder, index, workerCount))
      }
      PlannedForkGroup(forkGroup, label, folder, classes, workers)
    }
    groupsToRun match {
      // As Mill does, when no fork group has a class, fork group 0 runs with none, so the framework's setup and
      // teardown still run.
      case Seq() => Right(WorkerLayout(Seq(forkGroupOf(Nil, 0, ForkGroupLabel.of(0, 1), PathUnderModule()))))
      case Seq((classes, position)) => Right(WorkerLayout(Seq(forkGroupOf(classes, position, ForkGroupLabel.of(0, 1), PathUnderModule()))))
      case groups =>
        val queued = groups.zipWithIndex.map { case ((classes, position), indexAmongGroupsRun) =>
          val label = ForkGroupLabel.of(indexAmongGroupsRun, groups.size)
          forkGroupOf(classes, position, label, PathUnderModule(groupFolderName(classes, label)))
        }
        // Mill's `testForked` also fails when two fork groups share a `processFolder`: the second group's worker cannot
        // start.
        sharedProcessFolders(queued, filesystemIgnoresCase()).toLeft(WorkerLayout(queued))
    }
  }

  private def groupFolderName(classes: Seq[TestClassName], label: ForkGroupLabel): String =
    // Copies Mill 1.3.1's `testForked` folder names, so fork groups share a folder exactly where they would under Mill.
    classes match {
      case Seq(single) => single.value
      case multiple => s"group-${label.value}-${multiple.head.value}"
    }

  private def sharedProcessFolders(forkGroups: Seq[PlannedForkGroup], filesystemIgnoresCase: Boolean): Option[ForkGroupsShareProcessFolders] = {
    val folderOnDisk: String => String = if (filesystemIgnoresCase) _.toLowerCase(Locale.ROOT) else identity
    val sharing = forkGroups
      .groupBy(_.folder.segments.map(folderOnDisk))
      .values
      .filter(_.size > 1)
      .toSeq
      .sortBy(_.head.forkGroup.position)
      .map(sharingGroups => SharedProcessFolder(sharingGroups.map(_.forkGroup), sharingGroups.head.workers.head.processFolder))
    Option.when(sharing.nonEmpty)(ForkGroupsShareProcessFolders(sharing))
  }
}
