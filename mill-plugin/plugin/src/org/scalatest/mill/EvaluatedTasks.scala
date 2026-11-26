package org.scalatest.mill

import mill.api.Evaluator
import mill.api.ExecResult
import mill.api.Task
import mill.api.Val
import scala.annotation.tailrec

private[mill] final class EvaluatedTasks private (results: Map[Task[?], ExecResult[Val]]) {

  /**
   * The task's value where it succeeded. Otherwise the failure message of each failed task among it and the tasks
   * upstream of it, as `FailedTask.message` joins them.
   */
  def valueOrFailure[V](task: Task[V]): Either[String, V] =
    results.get(task).flatMap(_.asSuccess) match {
      case Some(success) => Right(success.value.value.asInstanceOf[V])
      case None => Left(FailedTask.message(failedAtOrUpstreamOf(task)))
    }

  private def failedAtOrUpstreamOf(task: Task[?]): Seq[FailedTask] =
    EvaluatedTasks.atOrUpstreamOf(List(task), Set.empty).toSeq.flatMap { upstream =>
      results.get(upstream).flatMap(_.asFailing).map(failing => FailedTask(EvaluatedTasks.nameOf(upstream), EvaluatedTasks.errorOf(failing)))
    }
}

private[mill] object EvaluatedTasks {

  def of(evaluation: Evaluator.Result[?]): EvaluatedTasks = new EvaluatedTasks(evaluation.executionResults.transitiveResults)

  @tailrec
  private def atOrUpstreamOf(pending: List[Task[?]], found: Set[Task[?]]): Set[Task[?]] =
    pending match {
      case Nil => found
      case task :: rest if found.contains(task) => atOrUpstreamOf(rest, found)
      case task :: rest => atOrUpstreamOf(task.inputs.toList ++ rest, found + task)
    }

  private def nameOf(task: Task[?]): Option[String] =
    task match {
      // A named task's segments are `private[mill]`; its `toString` renders them, as Mill writes the task's name.
      case named: Task.Named[?] => Some(named.toString)
      case _ => None
    }

  private def errorOf(failing: ExecResult.Failing[?]): String =
    failing match {
      case failure: ExecResult.Failure[?] => failure.msg
      // A failed Coursier resolution reaches a task as an exception whose message begins and ends with a line break.
      case exception: ExecResult.Exception => Option(exception.throwable.getMessage).fold(exception.throwable.toString)(_.strip)
    }
}
