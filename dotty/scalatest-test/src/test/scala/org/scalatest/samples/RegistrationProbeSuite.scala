package org.scalatest.samples

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import org.scalatest.DoNotDiscover
import org.scalatest.Suite
import org.scalatest.SuiteRegistration
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.tools.RegistrationInContext
import scala.jdk.CollectionConverters.*

@DoNotDiscover
class RegistrationProbeSuite extends AnyFunSuite {

  // Taken at construction, so a run that outlives its recording cannot write into a later one.
  private val recording = RegistrationProbeSuite.current.get

  test("probes its registration") {
    recording.add(this -> RegistrationInContext.ofCallingThread)
  }
}

object RegistrationProbeSuite {

  private val current = new AtomicReference(new ConcurrentLinkedQueue[(Suite, SuiteRegistration)])

  def instancesRunIn(launch: => Unit): Vector[(Suite, SuiteRegistration)] = synchronized {
    val recording = new ConcurrentLinkedQueue[(Suite, SuiteRegistration)]
    current.set(recording)
    launch
    recording.asScala.toVector
  }
}
