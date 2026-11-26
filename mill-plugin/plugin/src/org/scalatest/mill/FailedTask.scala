package org.scalatest.mill

private[mill] final case class FailedTask(name: Option[TaskName], error: String) {

  def line: String = name.fold(error)(taskName => s"${taskName.value} $error")
}

private[mill] object FailedTask {

  def message(failedTasks: Seq[FailedTask]): String = failedTasks.sortBy(_.name.fold("")(_.value)).map(_.line).mkString("\n")
}
