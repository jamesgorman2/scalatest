package org.scalatest.mill

opaque type FixtureName = String

object FixtureName {

  val arguments: FixtureName = "arguments"
  val brokenConstructor: FixtureName = "broken-constructor"
  val capturedOutput: FixtureName = "captured-output"
  val classGlobsAcrossModules: FixtureName = "class-globs-across-modules"
  val classGlobsAndFrameworkArguments: FixtureName = "class-globs-and-framework-arguments"
  val compatibilityFixture: FixtureName = "compatibility-fixture"
  val everyWorker: FixtureName = "every-worker"
  val jsonOutput: FixtureName = "json-output"
  val libraryWithoutMill: FixtureName = "library-without-mill"
  val millOwnTasks: FixtureName = "mill-own-tasks"
  val moduleSelectors: FixtureName = "module-selectors"
  val modulesTheTestCommandCannotRun: FixtureName = "modules-the-test-command-cannot-run"
  val munit: FixtureName = "munit"
  val result: FixtureName = "result"
  val severalModules: FixtureName = "several-modules"
  val styles: FixtureName = "styles"
  val upstreamScalaTest: FixtureName = "upstream-scalatest"
  val utest: FixtureName = "utest"

  def onScala(scalaVersion: String): FixtureName = s"scala-$scalaVersion"

  def withoutPluginOnScala(scalaVersion: String): FixtureName = s"without-plugin-scala-$scalaVersion"

  extension (name: FixtureName) {
    def value: String = name
  }
}
