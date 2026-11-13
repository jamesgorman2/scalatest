package compatibility.fixture

import org.scalatest.Ignore
import org.scalatest.refspec.RefSpec

class RefSpecSpec extends RefSpec {
  object `A scope` {
    def `succeeds in it`: Unit = assert(List(1, 2).sum == 3)

    def `fails in it`: Unit = assert(List(1, 2).sum == 4)

    @Ignore
    def `is ignored in it`: Unit = assert(List(1, 2).sum == 3)

    def `is pending in it`: Unit = pending
  }
}
