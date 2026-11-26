package org.scalatest.mill

import java.io.IOException
import java.util.Locale
import org.scalatest.consolidation.ForkGroup
import org.scalatest.consolidation.Worker
import org.scalatest.consolidation.WorkerIndex
import org.scalatest.events.TestClassName
import org.scalatest.mill.WorkerLayoutFailure.ClassGlobsKeepNoTestClass
import org.scalatest.mill.WorkerLayoutFailure.FilesystemCaseNotChecked
import org.scalatest.mill.WorkerLayoutFailure.ForkGroupsShareProcessFolders
import scala.util.control.NoStackTrace

private[mill] final case class WorkerLayout(forkGroups: Seq[PlannedForkGroup]) {

  def testClassCount: Int = forkGroups.map(_.testClasses.size).sum
}

private[mill] final case class PlannedForkGroup(
    id: ForkGroup,
    label: ForkGroupLabel,
    folder: PathUnderModule,
    testClasses: Seq[TestClassName],
    workers: Seq[PlannedWorker]
) {

  def workerIndexDigits: Int = PaddedIndex.digits(workers.size)

  def description: String = id.description
}

extension (forkGroup: ForkGroup) {

  private[mill] def description: String = s"fork group ${forkGroup.position}"
}

private[mill] final case class PlannedWorker(id: Worker, processFolder: PathUnderModule) {

  def description: String = s"worker ${id.index.value} of ${id.forkGroup.description}"
}

private[mill] enum WorkerLayoutFailure extends Exception with NoStackTrace {
  case ClassGlobsKeepNoTestClass(classGlobs: Seq[ClassGlob])
  case ForkGroupsShareProcessFolders(sharing: Seq[SharedProcessFolder])
  case FilesystemCaseNotChecked(cause: IOException)

  override def getMessage: String = message

  def message: String =
    this match {
      case ClassGlobsKeepNoTestClass(classGlobs) => ClassGlob.noTestMatchedMessage(classGlobs)
      case ForkGroupsShareProcessFolders(sharing) => sharing.map(_.message).mkString("; ")
      case FilesystemCaseNotChecked(cause) => s"checking whether the filesystem ignores case failed: $cause"
    }

  def moduleMessage: String =
    this match {
      // Unprefixed, as Mill's `testOnly` words it.
      case ClassGlobsKeepNoTestClass(_) => message
      case _ => s"Laying out the workers failed: $message"
    }
}

private[mill] final case class SharedProcessFolder(forkGroups: Seq[ForkGroup], folder: PathUnderModule) {

  def message: String = {
    s"fork groups ${EnglishList.of(forkGroups.map(_.position.toString))} share the processFolder ${folder.render}"
  }
}

private[mill] object WorkerLayout {

  def ofQueue(
      forkGroups: Seq[Seq[TestClassName]],
      discovered: Set[TestClassName],
      classGlobs: Seq[ClassGlob],
      testParallelism: Boolean,
      jobs: Int,
      filesystemIgnoresCase: () => Either[FilesystemCaseNotChecked, Boolean]
  ): Either[WorkerLayoutFailure, WorkerLayout] = {
    val keptForkGroups = forkGroups.map(_.filter(ClassGlob.keeping(classGlobs)).filter(discovered))
    // As Mill 1.3.1 does, workers get folders of their own under test parallelism unless the module queues exactly one
    // test class; a module that queues none still gets them.
    val workersPerGroup =
      if (testParallelism && keptForkGroups.map(_.size).sum != 1)
        WorkersPerGroup.UpToJobsInOwnFolders(jobs)
      else
        WorkersPerGroup.OneInGroupFolder
    of(keptForkGroups, classGlobs, workersPerGroup, filesystemIgnoresCase)
  }

  def ofBatches(
      forkGroups: Seq[Seq[TestClassName]],
      classGlobs: Seq[ClassGlob],
      filesystemIgnoresCase: () => Either[FilesystemCaseNotChecked, Boolean]
  ): Either[WorkerLayoutFailure, WorkerLayout] =
    of(forkGroups.map(_.filter(ClassGlob.keeping(classGlobs))), classGlobs, WorkersPerGroup.OneInGroupFolder, filesystemIgnoresCase)

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
      keptForkGroups: Seq[Seq[TestClassName]],
      classGlobs: Seq[ClassGlob],
      workersPerGroup: WorkersPerGroup,
      filesystemIgnoresCase: () => Either[FilesystemCaseNotChecked, Boolean]
  ): Either[WorkerLayoutFailure, WorkerLayout] = {
    val groupsToRun = keptForkGroups.zipWithIndex.filter { case (classes, _) => classes.nonEmpty }
    def forkGroupOf(classes: Seq[TestClassName], position: Int, label: ForkGroupLabel, folder: PathUnderModule): PlannedForkGroup = {
      val forkGroup = ForkGroup.at(position)
      val workerCount = workersPerGroup.count(classes)
      val workers = (0 until workerCount).map { index =>
        PlannedWorker(Worker(forkGroup, WorkerIndex(index)), workersPerGroup.processFolder(folder, index, workerCount))
      }
      PlannedForkGroup(forkGroup, label, folder, classes, workers)
    }
    groupsToRun match {
      // As Mill 1.3.1's `testOnly` does, class globs that keep no class start no worker.
      case Seq() if classGlobs.nonEmpty => Left(new ClassGlobsKeepNoTestClass(classGlobs))
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
        filesystemIgnoresCase().flatMap(ignoresCase => sharedProcessFolders(queued, ignoresCase).toLeft(WorkerLayout(queued)))
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
      .sortBy(_.head.id.position)
      .map(sharingGroups => SharedProcessFolder(sharingGroups.map(_.id), sharingGroups.head.workers.head.processFolder))
    Option.when(sharing.nonEmpty)(new ForkGroupsShareProcessFolders(sharing))
  }
}
