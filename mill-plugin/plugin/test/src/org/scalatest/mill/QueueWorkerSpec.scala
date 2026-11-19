package org.scalatest.mill

import org.scalatest.consolidation.ForkGroup
import org.scalatest.consolidation.TestRunLabel
import org.scalatest.consolidation.Worker
import org.scalatest.consolidation.WorkerIndex
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

final class QueueWorkerSpec extends AnyWordSpec with should.Matchers {

  private def firstWorkerOf(position: Int): Worker =
    (for {
      forkGroup <- ForkGroup.from(position)
      index <- WorkerIndex.from(0)
    } yield Worker(forkGroup, index)).fold(fail(_), identity)

  private val discovered: Set[String] = Set("samples.A1", "samples.A2", "samples.B1", "samples.B2")

  "Laying out the workers of a module's fork groups" when {
    "the module has one fork group" should {
      "give one worker of fork group 0 the group's classes, in the module's folder" in {
        QueueWorker.forEachForkGroup(Seq(Seq("samples.A1", "samples.B1")), discovered) shouldBe Right(
          Seq(QueueWorker(firstWorkerOf(0), Nil, Seq("samples.A1", "samples.B1")))
        )
      }
    }

    "the module has two fork groups" should {
      "give each group's first worker the group's classes, in a folder named by the group's index and first class" in {
        QueueWorker.forEachForkGroup(Seq(Seq("samples.B1", "samples.B2"), Seq("samples.A1", "samples.A2")), discovered) shouldBe Right(
          Seq(
            QueueWorker(firstWorkerOf(0), Seq("group-0-samples.B1"), Seq("samples.B1", "samples.B2")),
            QueueWorker(firstWorkerOf(1), Seq("group-1-samples.A1"), Seq("samples.A1", "samples.A2"))
          )
        )
      }
    }

    "a fork group holds one class" should {
      "name that group's folder by its class" in {
        QueueWorker.forEachForkGroup(Seq(Seq("samples.B1"), Seq("samples.A1", "samples.A2")), discovered).map(_.map(_.processFolder)) shouldBe Right(
          Seq(Seq("samples.B1"), Seq("group-1-samples.A1"))
        )
      }
    }

    "the module has ten fork groups" should {
      "pad each group folder's index to two digits" in {
        val groups = (0 until 10).map(index => Seq(s"samples.S$index", s"samples.T$index"))
        val folders = QueueWorker.forEachForkGroup(groups, groups.flatten.toSet).fold(fail(_), identity).map(_.processFolder)
        folders.take(2) shouldBe Seq(Seq("group-00-samples.S0"), Seq("group-01-samples.S1"))
        folders.last shouldBe Seq("group-09-samples.S9")
      }
    }

    "a fork group holds no discovered class" should {
      "give that group no worker, and keep the position of every later group" in {
        QueueWorker
          .forEachForkGroup(Seq(Seq("samples.Gone"), Seq("samples.B1", "samples.B2"), Seq("samples.A1", "samples.A2")), discovered)
          .map(_.map(worker => (worker.forkGroupAndIndex, worker.processFolder))) shouldBe Right(
          Seq((firstWorkerOf(1), Seq("group-0-samples.B1")), (firstWorkerOf(2), Seq("group-1-samples.A1")))
        )
      }

      "run the one group left in the module's folder" in {
        QueueWorker.forEachForkGroup(Seq(Seq("samples.Gone"), Seq("samples.A1")), discovered) shouldBe Right(
          Seq(QueueWorker(firstWorkerOf(1), Nil, Seq("samples.A1")))
        )
      }
    }

    "a fork group holds a class that was not discovered" should {
      "leave that class out of the group's queue" in {
        QueueWorker.forEachForkGroup(Seq(Seq("samples.A1", "samples.Gone")), discovered).map(_.map(_.testClasses)) shouldBe Right(
          Seq(Seq("samples.A1"))
        )
      }
    }

    "no fork group holds a discovered class" should {
      "give one worker of fork group 0 no class, in the module's folder" in {
        QueueWorker.forEachForkGroup(Seq(Seq("samples.Gone")), discovered) shouldBe Right(Seq(QueueWorker(firstWorkerOf(0), Nil, Nil)))
      }
    }

    "two fork groups each hold the same one class" should {
      "start no worker, naming the shared folder and the groups sharing it" in {
        QueueWorker.forEachForkGroup(Seq(Seq("samples.A1"), Seq("samples.B1", "samples.B2"), Seq("samples.A1")), discovered) shouldBe Left(
          "fork groups 0 and 2 share the processFolder samples.A1"
        )
      }
    }

    "two fork groups each hold one class, the classes differing only in case" should {
      "start no worker, as their folders are one folder on a case-insensitive filesystem" in {
        QueueWorker.forEachForkGroup(Seq(Seq("samples.A1"), Seq("samples.a1")), Set("samples.A1", "samples.a1")) shouldBe Left(
          "fork groups 0 and 1 share the processFolder samples.A1"
        )
      }
    }

    "the module has no fork group" should {
      "give one worker of fork group 0 no class, in the module's folder" in {
        QueueWorker.forEachForkGroup(Nil, discovered) shouldBe Right(Seq(QueueWorker(firstWorkerOf(0), Nil, Nil)))
      }
    }
  }

  "A worker" should {
    "be described by its index, its fork group and its module" in {
      QueueWorker(firstWorkerOf(1), Nil, Nil).describedIn(TestRunLabel("core.test")) shouldBe "worker 0 of fork group 1 of core.test"
    }
  }
}
