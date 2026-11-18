package org.scalatest.tools

import org.scalatest.InvocationContext
import org.scalatest.SuiteRegistration

private[scalatest] object RegistrationInContext {

  def ofCallingThread: SuiteRegistration =
    InvocationContext.get.collect { case ofRegisteredSuite: ContextOfRegisteredSuite => ofRegisteredSuite.registration }.getOrElse(SuiteRegistration.none)

  def isInATest: Boolean = InvocationContext.get.exists(_.isInstanceOf[InExecutionOfTest])
}
