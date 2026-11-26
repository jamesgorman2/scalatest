package org.scalatest.mill

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

final class FailedTaskSpec extends AnyWordSpec with should.Matchers {

  "A failed task's line" when {
    "the task has a name" should {
      "be the name, a space and the error" in {
        FailedTask(Some(TaskName("broken.test.compile")), "Compilation failed").line shouldBe "broken.test.compile Compilation failed"
      }
    }

    "the task has no name" should {
      "be the error alone" in {
        FailedTask(None, "Resolution failed").line shouldBe "Resolution failed"
      }
    }
  }

  "The message of several failed tasks" should {
    "hold one line for each, in order of task name, with a task that has no name first" in {
      val failedTasks = Seq(
        FailedTask(Some(TaskName("broken.test.generatedSources")), "generatedSources fails"),
        FailedTask(Some(TaskName("broken.compile")), "Compilation failed"),
        FailedTask(None, "Resolution failed")
      )
      FailedTask.message(failedTasks) shouldBe
        "Resolution failed\nbroken.compile Compilation failed\nbroken.test.generatedSources generatedSources fails"
    }
  }
}
