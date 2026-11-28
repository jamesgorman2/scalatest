package samples

import org.scalatest.funsuite.AnyFunSuite
import scala.scalajs.js

final class JsSuite extends AnyFunSuite {
  test("runs") {
    val markers = js.Dynamic.global.process.env.MARKERS.asInstanceOf[String]
    js.Dynamic.global.require("fs").writeFileSync(s"$markers/JsSuite.runs", "")
  }
}
