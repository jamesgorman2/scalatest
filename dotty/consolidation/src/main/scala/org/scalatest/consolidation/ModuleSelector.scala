package org.scalatest.consolidation

import org.scalatest.events.JsonValue

/** A module selector as the build tool was given it, with the test modules it resolved to. */
final case class ModuleSelector(text: String, modules: Vector[ModuleName]) {

  private[consolidation] def toJson: JsonValue =
    JsonOutputWriter.objectOf(
      Vector(
        "selector" -> Some(JsonValue.Text(text)),
        "modules" -> Some(JsonValue.Array(modules.map(module => JsonValue.Text(module.value))))
      )
    )
}
