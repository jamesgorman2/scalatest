package samples

@org.scalatest.DoNotDiscover
class UnicodeSuite extends org.scalatest.funsuite.AnyFunSuite {

  test("reports") {
    fail("first line\nsecond line: ü ß 日本 ✓")
  }
}
// Tests expect the fail call on line 7.
