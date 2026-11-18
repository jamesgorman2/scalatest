package org.scalatest.samples

import org.scalatest.DoNotDiscover
import org.scalatest.StopOnFailure
import org.scalatest.funsuite.AnyFunSuite

// In ScalaTest's package because StopOnFailure is private to it.
@DoNotDiscover
class StopSuite extends AnyFunSuite with StopOnFailure {

  test("a") {
    fail("a fails")
  }

  test("b") {
    succeed
  }
}
