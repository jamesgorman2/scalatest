package org.scalatest.events

private[scalatest] final class MalformedTestEventLogException(val line: String, val reason: String)
    extends IllegalStateException(s"$reason, in $line")
