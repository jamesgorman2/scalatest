package org.scalatest.consolidation

import org.scalatest.events.JsonReader
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

final class JsonOutputSpec extends AnyWordSpec with should.Matchers with ConsolidatedRuns with JsonOutputs {

  "The JSON output" when {
    "its text is many pieces long" should {
      "be given in pieces that join to the whole text, none ending part way through a surrogate pair" in {
        val message = "😀" * 10000
        var pieces = Vector.empty[String]
        val consolidation = Consolidation.start(new OutputSink, coloured = false, jsonOutput = Some(json => json.writeTo(piece => pieces :+= piece)))
        consolidation.addRootFailure(message)
        consolidation.end()
        pieces.size should be > 1
        pieces.filter(piece => Character.isHighSurrogate(piece.last)) shouldBe empty
        JsonReader.read(pieces.mkString)("failures").only("message") shouldBe text(message)
      }
    }
  }
}
