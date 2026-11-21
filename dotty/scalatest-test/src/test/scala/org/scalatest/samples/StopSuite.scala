package org.scalatest.samples

import org.scalatest.DoNotDiscover
import org.scalatest.StopOnFailure
import org.scalatest.funsuite.AnyFunSuite

@DoNotDiscover
class StopSuite extends AnyFunSuite with StopOnFailure {

  test("a") {
    fail("a fails")
  }

  test("b") {
    succeed
  }
}
