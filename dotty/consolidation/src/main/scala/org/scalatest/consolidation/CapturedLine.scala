package org.scalatest.consolidation

/** One line a process wrote to stdout or stderr, decoded, with its line terminator where it has one. */
enum CapturedLine {
  case Stdout(text: String)
  case Stderr(text: String)
}
