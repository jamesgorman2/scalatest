package org.scalatest.mill

import mill.testkit.IntegrationTester
import org.scalatest.matchers.should

final class FixtureBuild(tester: IntegrationTester) extends should.Matchers {

  private val buildFile: os.Path = tester.workspacePath / "build.mill"

  private val testModuleDependencies: String = """    def mvnDeps = Seq(mvn"com.edc::scalatest::3.2.20")"""

  def workspace: os.Path = tester.workspacePath

  def testSources: os.Path = workspace / "core" / "test" / "src"

  def samples: os.Path = testSources / "samples"

  def report: os.Path = workspace / "report.json"

  def buildText: String = os.read(buildFile)

  def editBuild(edit: String => String): Unit = os.write.over(buildFile, edit(buildText))

  def replaceInBuild(from: String, to: String): Unit =
    editBuild { build =>
      withClue(s"the build file holds no $from:\n$build\n") {
        build should include(from)
      }
      build.replace(from, to)
    }

  /** The build's header must hold no `mvnDeps`. */
  def addPlugin(): Unit = editBuild(build => s"${IntegrationEnvironment.pluginHeaderLine}\n$build")

  def removePlugin(): Unit = editBuild(_.linesIterator.filterNot(_.startsWith("//| mvnDeps:")).mkString("\n"))

  /** The build's test module must depend on `com.edc::scalatest::3.2.20`. */
  def addToTestModule(members: String*): Unit =
    replaceInBuild(testModuleDependencies, (testModuleDependencies +: members.map("    " + _)).mkString("\n"))

  def setModuleArguments(moduleArguments: Seq[String]): Unit =
    addToTestModule(moduleArguments.map(argument => s"\"$argument\"").mkString("def testArgsDefault = Seq(", ", ", ")"))

  def removeModuleArguments(): Unit = editBuild(_.linesIterator.filterNot(_.contains("testArgsDefault")).mkString("\n"))

  def appendToBuild(definition: String): Unit = editBuild(build => s"$build\n$definition\n")

  def addSuites(suites: String*): Unit = addSuiteFiles(suites.map(suite => workspace / "suites" / s"$suite.scala"))

  def addSuiteFiles(sources: Seq[os.Path]): Unit = sources.foreach(source => addSuiteFile(source, source.last))

  def addSuiteFile(source: os.Path, fileName: String): Unit = os.copy(source, samples / fileName, createFolders = true)

  def eval(arguments: Seq[String]): IntegrationTester.EvalResult = tester.eval(arguments)

  def runTestCommand(arguments: String*): TestCommandResult = runTestCommandAfter(Nil)(arguments*)

  def runTestCommandWithColour(arguments: String*): TestCommandResult = runTestCommandAfter(Seq("--color", "true"))(arguments*)

  /** The tester turns Mill's ticker off, and with it the prefix Mill puts on each line a task writes. */
  def runTestCommandWithTicker(arguments: String*): TestCommandResult = runTestCommandAfter(Seq("--ticker", "true"))(arguments*)

  def runTestCommandWithTickerAndColour(arguments: String*): TestCommandResult =
    runTestCommandAfter(Seq("--ticker", "true", "--color", "true"))(arguments*)

  def runTestCommandAfter(millArguments: Seq[String])(arguments: String*): TestCommandResult =
    TestCommandResult(tester.eval(millArguments ++ ("scalatest/test" +: arguments)))

  def runTestCommandWritingReport(): TestCommandResult = runTestCommand("core.test", "-fJ", report.toString)

  def reportJson: JsonNode = JsonNode.read(report)

  /** Deletes any earlier report, invokes the test command as `mill scalatest/test core.test -fJ <report>`, and parses the JSON output it writes. */
  def jsonOutputOfTestCommand(): JsonNode = {
    os.remove(report)
    val result = runTestCommandWritingReport()
    withClue(result.debugString) {
      os.exists(report) shouldBe true
    }
    reportJson
  }

  // Mill writes a `testargs` file for each worker it starts.
  def anyTestWorkerStarted: Boolean = os.exists(workspace / "out") && os.walk(workspace / "out").exists(_.last == "testargs")

  def eventLogFiles: Seq[os.Path] = os.walk(workspace / "out").filter(_.last == JvmTestModuleRun.eventLogFileName)

  /** A later invocation then writes nothing about compiling the build. */
  def compileBuildFile(): Unit = {
    val result = tester.eval(Seq("resolve", "_"))
    require(result.isSuccess, s"the build did not compile:\n${result.debugString}")
  }

  def removeModuleOutput(): Unit = os.remove.all(workspace / "out" / "core")

  def removeProcessIdFile(): Unit = tester.removeProcessIdFile()

  def withBspSession[A](stderrLog: os.Path)(use: BspClientSession => A): (A, Seq[String]) =
    BspClientSession.withSession(tester, IntegrationEnvironment.millExecutable, stderrLog)(use)
}
