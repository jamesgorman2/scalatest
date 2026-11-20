package org.scalatest.consolidation

/** A module selector as the build tool was given it, with the test modules it resolved to. */
final case class ModuleSelector(text: String, modules: Vector[TestRunLabel])
