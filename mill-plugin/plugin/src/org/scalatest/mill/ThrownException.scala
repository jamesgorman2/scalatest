package org.scalatest.mill

import mill.api.Result

private[mill] final case class ThrownException(className: String, message: Option[String], cause: Option[ThrownException]) {

  // Named by its message where it has one, as Mill writes a task's error. An exception with no message, such as an
  // `ExceptionInInitializerError`, is named by its class, and each cause follows, as the causes carry what went wrong.
  def error: String = (message.getOrElse(className) +: causes.map(cause => s"caused by ${cause.description}")).mkString(", ")

  private def description: String = message.fold(className)(text => s"$className: $text")

  private def causes: Seq[ThrownException] = cause.toSeq.flatMap(direct => direct +: direct.causes)
}

private[mill] object ThrownException {

  def from(throwable: Throwable): ThrownException = {
    val cause = Option(throwable.getCause).map(from)
    throwable match {
      // Mill can replace an exception, as it does one returned over RPC, with one of its own class. Only `info` holds
      // the original's class name.
      case serialized: Result.SerializedException => ThrownException(serialized.info.clsName, Option(serialized.getMessage), cause)
      case other => ThrownException(other.getClass.getName, Option(other.getMessage), cause)
    }
  }
}
