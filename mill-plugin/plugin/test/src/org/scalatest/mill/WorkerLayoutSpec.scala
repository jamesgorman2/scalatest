package org.scalatest.mill

import org.scalatest.consolidation.ForkGroup
import org.scalatest.consolidation.TestRunLabel
import org.scalatest.consolidation.Worker
import org.scalatest.consolidation.WorkerIndex
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

final class WorkerLayoutSpec extends AnyWordSpec with should.Matchers {

  private def workerOf(position: Int, index: Int = 0): Worker =
    (for {
      forkGroup <- ForkGroup.from(position)
      workerIndex <- WorkerIndex.from(index)
    } yield Worker(forkGroup, workerIndex)).fold(fail(_), identity)

  private def forkGroupAt(position: Int): ForkGroup = workerOf(position).forkGroup

  private val discovered: Set[String] = Set("samples.A1", "samples.A2", "samples.B1", "samples.B2", "samples.B3")

  private def layoutOf(
      forkGroups: Seq[Seq[String]],
      discovered: Set[String] = discovered,
      testParallelism: Boolean = false,
      jobs: Int = 4,
      filesystemIgnoresCase: Boolean = false
  ): Either[String, WorkerLayout] =
    WorkerLayout.ofQueue(forkGroups, discovered, testParallelism, jobs, () => Right(filesystemIgnoresCase))

  private def workersOf(layout: Either[String, WorkerLayout]): Either[String, Seq[PlannedWorker]] = layout.map(_.forkGroups.flatMap(_.workers))

  private def processFoldersOf(layout: Either[String, WorkerLayout]): Either[String, Seq[Seq[String]]] = workersOf(layout).map(_.map(_.processFolder))

  "Laying out a module's queue" when {
    "test parallelism is off" when {
      "the module has one fork group" should {
        "give the group one worker in the module's folder, and queue the group's classes there" in {
          layoutOf(Seq(Seq("samples.A1", "samples.B1"))) shouldBe Right(
            WorkerLayout(2, Seq(PlannedForkGroup(forkGroupAt(0), "0", Nil, Seq("samples.A1", "samples.B1"), 1, Seq(PlannedWorker(workerOf(0), Nil)))))
          )
        }
      }

      "the module has two fork groups" should {
        "give each group one worker, in a folder named by the group's index and first class" in {
          layoutOf(Seq(Seq("samples.B1", "samples.B2"), Seq("samples.A1", "samples.A2"))) shouldBe Right(
            WorkerLayout(
              4,
              Seq(
                PlannedForkGroup(forkGroupAt(0), "0", Seq("group-0-samples.B1"), Seq("samples.B1", "samples.B2"), 1, Seq(PlannedWorker(workerOf(0), Seq("group-0-samples.B1")))),
                PlannedForkGroup(forkGroupAt(1), "1", Seq("group-1-samples.A1"), Seq("samples.A1", "samples.A2"), 1, Seq(PlannedWorker(workerOf(1), Seq("group-1-samples.A1"))))
              )
            )
          )
        }
      }

      "a fork group holds one class" should {
        "name that group's folder by its class" in {
          processFoldersOf(layoutOf(Seq(Seq("samples.B1"), Seq("samples.A1", "samples.A2")))) shouldBe Right(
            Seq(Seq("samples.B1"), Seq("group-1-samples.A1"))
          )
        }
      }

      "the module has ten fork groups" should {
        "pad each group's label and folder index to two digits" in {
          val groups = (0 until 10).map(index => Seq(s"samples.S$index", s"samples.T$index"))
          val forkGroups = layoutOf(groups, groups.flatten.toSet).fold(fail(_), identity).forkGroups
          forkGroups.take(2).map(group => (group.label, group.folder)) shouldBe Seq(("00", Seq("group-00-samples.S0")), ("01", Seq("group-01-samples.S1")))
          forkGroups.last.folder shouldBe Seq("group-09-samples.S9")
        }
      }

      "a fork group holds no discovered class" should {
        "not run that group, and keep the position of every later group" in {
          workersOf(layoutOf(Seq(Seq("samples.Gone"), Seq("samples.B1", "samples.B2"), Seq("samples.A1", "samples.A2")))) shouldBe Right(
            Seq(PlannedWorker(workerOf(1), Seq("group-0-samples.B1")), PlannedWorker(workerOf(2), Seq("group-1-samples.A1")))
          )
        }

        "run the one group left in the module's folder" in {
          workersOf(layoutOf(Seq(Seq("samples.Gone"), Seq("samples.A1")))) shouldBe Right(Seq(PlannedWorker(workerOf(1), Nil)))
        }
      }

      "a fork group holds a class that was not discovered" should {
        "leave that class out of the group's queue" in {
          layoutOf(Seq(Seq("samples.A1", "samples.Gone"))).map(_.forkGroups.map(_.testClasses)) shouldBe Right(Seq(Seq("samples.A1")))
        }
      }
    }

    "test parallelism is on" when {
      "a fork group holds fewer classes than the jobs" should {
        "give the group one worker for each class, each in its own folder in the group's folder" in {
          processFoldersOf(layoutOf(Seq(Seq("samples.B1", "samples.B2", "samples.B3"), Seq("samples.A1", "samples.A2")), testParallelism = true)) shouldBe Right(
            Seq(
              Seq("group-0-samples.B1", "worker-0"),
              Seq("group-0-samples.B1", "worker-1"),
              Seq("group-0-samples.B1", "worker-2"),
              Seq("group-1-samples.A1", "worker-0"),
              Seq("group-1-samples.A1", "worker-1")
            )
          )
        }

        "index the group's workers from 0, in their fork group" in {
          workersOf(layoutOf(Seq(Seq("samples.B1"), Seq("samples.A1", "samples.A2")), testParallelism = true)).map(_.map(_.forkGroupAndIndex)) shouldBe Right(
            Seq(workerOf(0), workerOf(1), workerOf(1, 1))
          )
        }
      }

      "a fork group holds more classes than the jobs" should {
        "give the group as many workers as the jobs" in {
          layoutOf(Seq(Seq("samples.A1", "samples.A2", "samples.B1")), testParallelism = true, jobs = 2).map(_.forkGroups.map(_.workers.size)) shouldBe Right(Seq(2))
        }
      }

      "a fork group has ten workers" should {
        "pad each worker's index to two digits in its folder's name" in {
          val classes = (0 until 10).map(index => s"samples.S$index")
          val workers = workersOf(layoutOf(Seq(classes), classes.toSet, testParallelism = true, jobs = 10)).fold(fail(_), identity)
          workers.take(2).map(_.processFolder) shouldBe Seq(Seq("worker-00"), Seq("worker-01"))
          workers.last.forkGroupAndIndex shouldBe workerOf(0, 9)
          layoutOf(Seq(classes), classes.toSet, testParallelism = true, jobs = 10).map(_.forkGroups.map(_.workerIndexLength)) shouldBe Right(Seq(2))
        }
      }

      "the module queues exactly one class" should {
        "give its fork group one worker in the group's folder, as Mill runs it without parallelism" in {
          processFoldersOf(layoutOf(Seq(Seq("samples.Gone"), Seq("samples.A1")), testParallelism = true)) shouldBe Right(Seq(Nil))
        }
      }

      "no fork group holds a discovered class" should {
        "give fork group 0 one worker with no class, in a folder of its own" in {
          layoutOf(Seq(Seq("samples.Gone")), testParallelism = true) shouldBe Right(
            WorkerLayout(0, Seq(PlannedForkGroup(forkGroupAt(0), "0", Nil, Nil, 1, Seq(PlannedWorker(workerOf(0), Seq("worker-0"))))))
          )
        }
      }

      "the jobs are 1" should {
        "give each fork group one worker, still in a folder of its own" in {
          processFoldersOf(layoutOf(Seq(Seq("samples.A1", "samples.A2")), testParallelism = true, jobs = 1)) shouldBe Right(Seq(Seq("worker-0")))
        }
      }
    }

    "no fork group holds a discovered class" should {
      "give fork group 0 one worker with no class, in the module's folder" in {
        layoutOf(Seq(Seq("samples.Gone"))) shouldBe Right(
          WorkerLayout(0, Seq(PlannedForkGroup(forkGroupAt(0), "0", Nil, Nil, 1, Seq(PlannedWorker(workerOf(0), Nil)))))
        )
      }
    }

    "the module has no fork group" should {
      "give fork group 0 one worker with no class, in the module's folder" in {
        workersOf(layoutOf(Nil)) shouldBe Right(Seq(PlannedWorker(workerOf(0), Nil)))
      }
    }

    "two fork groups each hold the same one class" should {
      "give no layout, naming the shared processFolder and the groups sharing it" in {
        layoutOf(Seq(Seq("samples.A1"), Seq("samples.B1", "samples.B2"), Seq("samples.A1"))) shouldBe Left(
          "fork groups 0 and 2 share the processFolder samples.A1"
        )
      }

      "under test parallelism, name the processFolder of the groups' first worker" in {
        layoutOf(Seq(Seq("samples.A1"), Seq("samples.A1")), testParallelism = true) shouldBe Left(
          "fork groups 0 and 1 share the processFolder samples.A1/worker-0"
        )
      }
    }

    "two fork groups each hold one class, the classes differing only in case" when {
      "the filesystem ignores case" should {
        "give no layout, naming the shared processFolder and the groups sharing it" in {
          layoutOf(Seq(Seq("samples.A1"), Seq("samples.a1")), Set("samples.A1", "samples.a1"), filesystemIgnoresCase = true) shouldBe Left(
            "fork groups 0 and 1 share the processFolder samples.A1"
          )
        }
      }

      "the filesystem is case-sensitive" should {
        "give each group's worker a folder of its own" in {
          processFoldersOf(layoutOf(Seq(Seq("samples.A1"), Seq("samples.a1")), Set("samples.A1", "samples.a1"))) shouldBe Right(
            Seq(Seq("samples.A1"), Seq("samples.a1"))
          )
        }
      }
    }

    "checking whether the filesystem ignores case fails" when {
      val failingCheck: () => Either[String, Boolean] = () => Left("checking whether the filesystem ignores case failed: disk full")

      "two fork groups hold a discovered class" should {
        "give no layout, giving why the check failed" in {
          WorkerLayout.ofQueue(Seq(Seq("samples.B1"), Seq("samples.A1")), discovered, false, 4, failingCheck) shouldBe Left(
            "checking whether the filesystem ignores case failed: disk full"
          )
        }
      }

      "one fork group holds a discovered class" should {
        "give that group's worker, without checking" in {
          workersOf(WorkerLayout.ofQueue(Seq(Seq("samples.Gone"), Seq("samples.A1")), discovered, false, 4, failingCheck)) shouldBe Right(
            Seq(PlannedWorker(workerOf(1), Nil))
          )
        }
      }

      "no fork group holds a discovered class" should {
        "give fork group 0's worker, without checking" in {
          workersOf(WorkerLayout.ofQueue(Seq(Seq("samples.Gone")), discovered, false, 4, failingCheck)) shouldBe Right(Seq(PlannedWorker(workerOf(0), Nil)))
        }
      }
    }
  }

  "Laying out a module's batch workers" when {
    "the module has two fork groups" should {
      "give each group one worker, index 0, in the group's folder, given the group's whole class list" in {
        WorkerLayout.ofBatches(Seq(Seq("samples.B1", "samples.B2"), Seq("samples.A1", "samples.A2")), () => Right(false)) shouldBe Right(
          WorkerLayout(
            4,
            Seq(
              PlannedForkGroup(forkGroupAt(0), "0", Seq("group-0-samples.B1"), Seq("samples.B1", "samples.B2"), 1, Seq(PlannedWorker(workerOf(0), Seq("group-0-samples.B1")))),
              PlannedForkGroup(forkGroupAt(1), "1", Seq("group-1-samples.A1"), Seq("samples.A1", "samples.A2"), 1, Seq(PlannedWorker(workerOf(1), Seq("group-1-samples.A1"))))
            )
          )
        )
      }
    }

    "a fork group holds no class" should {
      "not run that group, and keep the position of every later group" in {
        workersOf(WorkerLayout.ofBatches(Seq(Nil, Seq("samples.B1"), Seq("samples.A1")), () => Right(false))) shouldBe Right(
          Seq(PlannedWorker(workerOf(1), Seq("samples.B1")), PlannedWorker(workerOf(2), Seq("samples.A1")))
        )
      }
    }

    "the module has no fork group" should {
      "give fork group 0 one worker with no class, in the module's folder" in {
        WorkerLayout.ofBatches(Nil, () => Right(false)) shouldBe Right(
          WorkerLayout(0, Seq(PlannedForkGroup(forkGroupAt(0), "0", Nil, Nil, 1, Seq(PlannedWorker(workerOf(0), Nil)))))
        )
      }
    }

    "two fork groups each hold the same one class" should {
      "give no layout, naming the shared processFolder and the groups sharing it" in {
        WorkerLayout.ofBatches(Seq(Seq("samples.A1"), Seq("samples.A1")), () => Right(false)) shouldBe Left(
          "fork groups 0 and 1 share the processFolder samples.A1"
        )
      }
    }
  }

  "A worker" should {
    "be described by its index, its fork group and its module" in {
      PlannedWorker(workerOf(1, 2), Nil).describedIn(TestRunLabel("core.test")) shouldBe "worker 2 of fork group 1 of core.test"
    }
  }

  "A fork group" should {
    "be described by its position and its module" in {
      PlannedForkGroup(forkGroupAt(1), "0", Nil, Nil, 1, Nil).describedIn(TestRunLabel("core.test")) shouldBe "fork group 1 of core.test"
    }
  }
}
