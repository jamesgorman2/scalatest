package org.scalatest.consolidation

import org.scalatest.events.JsonReader
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

final class JsonOutputSpec extends AnyWordSpec with should.Matchers with ConsolidatedRuns with JsonOutputs {

  private def piecesOf(message: String): Vector[String] = {
    var pieces = Vector.empty[String]
    val consolidation = Consolidation.start(new OutputSink, coloured = false, jsonOutput = Some(json => json.writeTo(piece => pieces :+= piece)))
    consolidation.addRootFailure(message)
    consolidation.end()
    pieces
  }

  "The JSON output" when {
    "its text is many pieces long" should {
      "be given in pieces that join to the whole text" in {
        val message = "😀" * 10000
        val pieces = piecesOf(message)
        pieces.size should be > 1
        JsonReader.read(pieces.mkString)("failures").only("message") shouldBe text(message)
      }
      "end no piece part way through a surrogate pair, whether the text before the pairs is of even or odd length" in {
        val pieces = Vector("😀" * 10000, "x" + "😀" * 10000).flatMap(piecesOf)
        pieces.filter(piece => Character.isHighSurrogate(piece.last)) shouldBe empty
      }
    }
  }
}
