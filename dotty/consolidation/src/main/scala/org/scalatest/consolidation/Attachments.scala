package org.scalatest.consolidation

private[consolidation] final case class Attachments(failures: Vector[String], output: Vector[CapturedLine]) {

  def withFailure(message: String): Attachments = copy(failures = failures :+ message)

  def withOutput(line: CapturedLine): Attachments = copy(output = output :+ line)
}

private[consolidation] object Attachments {

  val none: Attachments = Attachments(Vector.empty, Vector.empty)
}
