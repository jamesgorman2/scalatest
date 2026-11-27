package org.scalatest.mill

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

final class ThrownExceptionSpec extends AnyWordSpec with should.Matchers {

  private val cause = ThrownException("java.lang.IllegalStateException", Some("samples.Throwing fails to load"), None)

  "A thrown exception's error" when {
    "the exception has a message and no cause" should {
      "be the message alone" in {
        ThrownException("java.lang.RuntimeException", Some("generatedSources throws"), None).error shouldBe "generatedSources throws"
      }
    }

    "the exception has no message" should {
      "name its class, then each cause by class and message" in {
        ThrownException("java.lang.ExceptionInInitializerError", None, Some(cause)).error shouldBe
          "java.lang.ExceptionInInitializerError, caused by java.lang.IllegalStateException: samples.Throwing fails to load"
      }
    }

    "the exception has a message and causes" should {
      "give the message, then each cause in order, outermost first, a cause without a message by its class alone" in {
        val innermost = ThrownException("java.io.IOException", None, None)
        ThrownException("java.lang.RuntimeException", Some("wrapped"), Some(cause.copy(cause = Some(innermost)))).error shouldBe
          "wrapped, caused by java.lang.IllegalStateException: samples.Throwing fails to load, caused by java.io.IOException"
      }
    }
  }
}
