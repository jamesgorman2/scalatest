package org.scalatest.mill

import mill.api.Result

private[mill] final case class ThrownException(className: String, message: Option[String], cause: Option[ThrownException]) {

  // The exception's message, as Mill writes a task's error, or its class name where it has none, such as an
  // `ExceptionInInitializerError`. Each cause follows, since a cause can carry what went wrong.
  def error: String = (message.getOrElse(className) +: causes.map(cause => s"caused by ${cause.description}")).mkString(", ")

  private def description: String = message.fold(className)(text => s"$className: $text")

  private def causes: Seq[ThrownException] = cause.toSeq.flatMap(direct => direct +: direct.causes)
}

private[mill] object ThrownException {

  def from(throwable: Throwable): ThrownException = {
    val className = throwable match {
      // Mill can replace an exception with a `SerializedException`, as it does one returned over RPC. Only its `info`
      // holds the original's class name.
      case serialized: Result.SerializedException => serialized.info.clsName
      case other => other.getClass.getName
    }
    ThrownException(className, Option(throwable.getMessage), Option(throwable.getCause).map(from))
  }
}
