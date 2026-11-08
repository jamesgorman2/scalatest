// PROTOTYPE - throwaway. One suite in a module that sets discoverTestsWithZinc, so its
// aheadOfTimeDiscoveredTestClassesIfNeeded yields Some(...).
package zinc

import org.scalatest.funsuite.AnyFunSuite

class Z1 extends AnyFunSuite {
  test("z") {
    System.out.println("Z-OUT one")
    System.err.println("Z-ERR one")
  }
}
