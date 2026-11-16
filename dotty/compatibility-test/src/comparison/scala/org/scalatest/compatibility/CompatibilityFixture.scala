package org.scalatest.compatibility

object CompatibilityFixture {
  val stdoutLine: String = "a line on stdout"
  val stderrLine: String = "a line on stderr"

  val suiteClassNames: Seq[String] =
    List(
      "AnyFunSuiteSpec",
      "AsyncFunSuiteSpec",
      "FixtureAnyFunSuiteSpec",
      "FixtureAsyncFunSuiteSpec",
      "AnyPropSpecSpec",
      "FixtureAnyPropSpecSpec",
      "AnyFunSpecSpec",
      "AsyncFunSpecSpec",
      "FixtureAnyFunSpecSpec",
      "FixtureAsyncFunSpecSpec",
      "PathAnyFunSpecSpec",
      "AnyFreeSpecSpec",
      "AsyncFreeSpecSpec",
      "FixtureAnyFreeSpecSpec",
      "FixtureAsyncFreeSpecSpec",
      "PathAnyFreeSpecSpec",
      "AnyWordSpecSpec",
      "AsyncWordSpecSpec",
      "FixtureAnyWordSpecSpec",
      "FixtureAsyncWordSpecSpec",
      "AnyFlatSpecSpec",
      "AsyncFlatSpecSpec",
      "FixtureAnyFlatSpecSpec",
      "FixtureAsyncFlatSpecSpec",
      "AnyFeatureSpecSpec",
      "AsyncFeatureSpecSpec",
      "FixtureAnyFeatureSpecSpec",
      "FixtureAsyncFeatureSpecSpec",
      "RefSpecSpec",
      "NestingSpec",
      "BeforeAllThrowsSpec",
      "ConfigMapWrappedSpec"
    ).map("compatibility.fixture." + _)

  private val jvmOnlySuiteClassNames: Set[String] =
    Set("RefSpecSpec", "ConfigMapWrappedSpec").map("compatibility.fixture." + _)

  val suiteClassNamesOnEveryRuntime: Seq[String] = suiteClassNames.filterNot(jvmOnlySuiteClassNames)

  /** Suites whose one test sends `timedMessage`, then `timedMessageCalled` naming when it called the first, then waits before closing. */
  val messageCallTimesSuiteClassNames: Seq[String] =
    List("AnyFunSuiteMessageCallTimesSpec", "PathAnyFunSpecMessageCallTimesSpec").map("compatibility.timing." + _)

  val timedMessage: String = "a timed message"

  val timedMessageCalled: scala.util.matching.Regex = """a timed message was called between (\d+) and (\d+)""".r
}
