package org.scalatest.consolidation

import org.scalatest.events.JsonValue

/** The build tool that started the runs, and its running version. */
final case class BuildTool(name: String, version: String) {

  private[consolidation] def toJson: JsonValue =
    JsonOutputWriter.objectOf(Vector("name" -> Some(JsonValue.Text(name)), "version" -> Some(JsonValue.Text(version))))
}
