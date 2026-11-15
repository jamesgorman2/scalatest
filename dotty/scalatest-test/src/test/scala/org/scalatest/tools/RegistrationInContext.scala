package org.scalatest.tools

import org.scalatest.InvocationContext
import org.scalatest.SuiteRegistration

private[scalatest] object RegistrationInContext {

  /** The registration whose run, or one of whose tests' executions, the calling thread runs in, else `SuiteRegistration.none`. */
  def ofCallingThread: SuiteRegistration =
    InvocationContext.get.collect { case ofRegisteredSuite: ContextOfRegisteredSuite => ofRegisteredSuite.registration }.getOrElse(SuiteRegistration.none)

  /** Whether the calling thread runs in an execution of a test. */
  def isInATest: Boolean = InvocationContext.get.exists(_.isInstanceOf[InExecutionOfTest])
}
