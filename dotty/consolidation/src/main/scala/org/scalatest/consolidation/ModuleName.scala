package org.scalatest.consolidation

/** A module's name, such as `core.test`. */
opaque type ModuleName = String

object ModuleName {

  def apply(value: String): ModuleName = value

  extension (name: ModuleName) {
    def value: String = name
  }
}
