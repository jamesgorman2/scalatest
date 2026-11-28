package org.scalatest.mill

private[mill] opaque type LibraryVersion = String

private[mill] object LibraryVersion {

  def apply(value: String): LibraryVersion = value

  extension (version: LibraryVersion) {
    def value: String = version
  }
}
