package org.scalatest.compatibility

object CompatibilityFixture {
  val stdoutLine: String = "a line on stdout"
  val stderrLine: String = "a line on stderr"

  val suiteClassNamesOnEveryRuntime: Seq[String] =
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
      "NestingSpec",
      "BeforeAllThrowsSpec"
    ).map("compatibility.fixture." + _)

  val suiteClassNames: Seq[String] =
    suiteClassNamesOnEveryRuntime ++ List("RefSpecSpec", "ConfigMapWrappedSpec").map("compatibility.fixture." + _)
}
