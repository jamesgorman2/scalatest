package org.scalatest.mill

private[mill] opaque type TaskName = String

private[mill] object TaskName {

  def apply(value: String): TaskName = value

  extension (name: TaskName) {
    def value: String = name
  }
}
