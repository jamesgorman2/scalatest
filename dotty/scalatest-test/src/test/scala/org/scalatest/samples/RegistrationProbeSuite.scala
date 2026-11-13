package org.scalatest.samples

import java.util.concurrent.ConcurrentLinkedQueue
import org.scalatest.DoNotDiscover
import org.scalatest.Suite
import org.scalatest.SuiteRegistration
import org.scalatest.funsuite.AnyFunSuite
import scala.jdk.CollectionConverters.*

// In ScalaTest's package because a suite instance's registration is private to it.
@DoNotDiscover
class RegistrationProbeSuite extends AnyFunSuite {

  test("probes its registration") {
    RegistrationProbeSuite.ran.add(this -> SuiteRegistration.of(this))
  }
}

object RegistrationProbeSuite {

  private val ran = new ConcurrentLinkedQueue[(Suite, SuiteRegistration)]

  /** Returns each instance that ran in the runs `launch` starts, with the registration it had as it ran. One caller records at a time. */
  def instancesRunIn(launch: => Unit): Vector[(Suite, SuiteRegistration)] = synchronized {
    ran.clear()
    try {
      launch
      ran.asScala.toVector
    } finally {
      ran.clear()
    }
  }
}
