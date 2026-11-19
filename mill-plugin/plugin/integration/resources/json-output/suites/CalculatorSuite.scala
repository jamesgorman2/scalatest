package samples
import org.scalatest.funsuite.AnyFunSuite

final class CalculatorSuite extends AnyFunSuite {
  test("adds two numbers") {
    print("dividing now\n")
    assert(1 + 1 == 2)
  }
}
