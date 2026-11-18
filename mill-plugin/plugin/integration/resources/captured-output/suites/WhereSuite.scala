package samples

import org.scalatest.funsuite.AnyFunSuite

final class WhereSuite extends AnyFunSuite {
  test("reports where") {
    println(s"env=${sys.env.getOrElse("FIXTURE_ENV", "")}")
    println(s"cwd=${System.getProperty("user.dir")}")
  }
}
