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
}
