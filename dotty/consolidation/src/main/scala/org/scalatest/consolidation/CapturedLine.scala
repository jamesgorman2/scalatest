package org.scalatest.consolidation

/** One line a run's process wrote, captured from its stdout or stderr, decoded and without its line end. */
enum CapturedLine {
  case Stdout(text: String)
  case Stderr(text: String)
}
