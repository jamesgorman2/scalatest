package scalatest

/** Gives the test command the prefix `scalatest`, so that `mill scalatest/test <module selector>` runs it. */
object `package` extends _root_.mill.api.ExternalModule.Alias(_root_.org.scalatest.mill.TestCommandModule)
