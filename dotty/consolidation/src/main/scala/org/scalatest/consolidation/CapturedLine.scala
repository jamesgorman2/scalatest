package org.scalatest.consolidation

/** One line a run's process wrote to stdout or stderr, decoded and without its line end. */
enum CapturedLine {
  case Stdout(text: String)
  case Stderr(text: String)
}
