package org.scalatest.mill

/** A task that did not succeed, with its name as Mill writes it where it has one, and its error. */
private[mill] final case class FailedTask(name: Option[String], error: String) {

  def line: String = name.fold(error)(taskName => s"$taskName $error")
}

private[mill] object FailedTask {

  /** One line for each failed task, in order of task name. A task with no name comes first. */
  def message(failedTasks: Seq[FailedTask]): String = failedTasks.sortBy(_.name.getOrElse("")).map(_.line).mkString("\n")
}
