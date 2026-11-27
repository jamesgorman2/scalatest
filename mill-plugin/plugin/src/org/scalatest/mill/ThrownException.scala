package org.scalatest.mill

private[mill] final case class ThrownException(className: String, message: Option[String], cause: Option[ThrownException]) {

  // Named by its message where it has one, as Mill writes a task's error. An exception with no message, such as an
  // `ExceptionInInitializerError`, is named by its class, and each cause follows, as the causes carry what went wrong.
  def error: String = (message.getOrElse(className) +: causes.map(cause => s"caused by ${cause.description}")).mkString(", ")

  private def description: String = message.fold(className)(text => s"$className: $text")

  private def causes: Seq[ThrownException] = cause.toSeq.flatMap(direct => direct +: direct.causes)
}
