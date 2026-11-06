// PROTOTYPE - throwaway. Passes; its module's tests start late (see `late.delay` in build.mill).
package late

import org.scalatest.funsuite.AnyFunSuite

class LateSuite extends AnyFunSuite {
  test("ok1") { Thread.sleep(200) }
  test("ok2") { Thread.sleep(200) }
}
