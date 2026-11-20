package org.scalatest.events

private[scalatest] final class MalformedJsonException(val text: String, val reason: String)
    extends IllegalArgumentException(s"$reason, in $text")
