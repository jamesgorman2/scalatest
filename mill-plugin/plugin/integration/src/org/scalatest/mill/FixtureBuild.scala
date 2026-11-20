package org.scalatest.mill

import mill.testkit.IntegrationTester

final class FixtureBuild(tester: IntegrationTester) {

  import org.scalatest.matchers.should.Matchers.{include, should, shouldBe, withClue}

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

  def removePlugin(): Unit = removeBuildLines("header line //| mvnDeps:")(_.startsWith("//| mvnDeps:"))

  /** The build's test module must depend on `com.edc::scalatest::3.2.20`. */
  def addToTestModule(members: String*): Unit =
    replaceInBuild(testModuleDependencies, (testModuleDependencies +: members.map("    " + _)).mkString("\n"))

  def setModuleArguments(moduleArguments: Seq[String]): Unit =
    addToTestModule(moduleArguments.map(argument => s"\"$argument\"").mkString("def testArgsDefault = Seq(", ", ", ")"))

  def removeModuleArguments(): Unit = removeBuildLines("line holding testArgsDefault")(_.contains("testArgsDefault"))

  private def removeBuildLines(description: String)(matches: String => Boolean): Unit =
    editBuild { build =>
      withClue(s"the build file holds no $description:\n$build\n") {
        build.linesIterator.exists(matches) shouldBe true
      }
      build.linesIterator.filterNot(matches).mkString("\n")
    }

  def appendToBuild(definition: String): Unit = editBuild(build => s"$build\n$definition\n")

  def addSuites(suites: String*): Unit = addSuiteFiles(suites.map(suite => workspace / "suites" / s"$suite.scala"))

  def addSuiteFiles(sources: Seq[os.Path]): Unit = sources.foreach(source => addSuiteFile(source, source.last))

  def addSuiteFile(source: os.Path, fileName: String): Unit = os.copy(source, samples / fileName, createFolders = true)

  def eval(arguments: Seq[String]): IntegrationTester.EvalResult = tester.eval(arguments)

  def runTestCommand(arguments: String*): TestCommandResult = runTestCommandWithMillArguments(Nil)(arguments*)

  def runTestCommandWithColour(arguments: String*): TestCommandResult = runTestCommandWithMillArguments(Seq("--color", "true"))(arguments*)

  /** The tester turns Mill's ticker off, and with it the prefix Mill puts on each line a task writes. */
  def runTestCommandWithTicker(arguments: String*): TestCommandResult = runTestCommandWithMillArguments(Seq("--ticker", "true"))(arguments*)

  def runTestCommandWithTickerAndColour(arguments: String*): TestCommandResult =
    runTestCommandWithMillArguments(Seq("--ticker", "true", "--color", "true"))(arguments*)

  def runTestCommandWithMillArguments(millArguments: Seq[String])(arguments: String*): TestCommandResult =
    TestCommandResult(tester.eval(millArguments ++ ("scalatest/test" +: arguments)))

  def runTestCommandWritingReport(millArguments: Seq[String] = Nil): TestCommandResult =
    runTestCommandWithMillArguments(millArguments)("core.test", "-fJ", report.toString)

  def reportJson: JsonNode = JsonNode.read(report)

  def resultAndReportOfTestCommand(millArguments: Seq[String] = Nil): (TestCommandResult, JsonNode) = {
    os.remove(report)
    val result = runTestCommandWritingReport(millArguments)
    withClue(result.debugString) {
      os.exists(report) shouldBe true
    }
    (result, reportJson)
  }

  def jsonOutputOfTestCommand(): JsonNode = {
    val (_, json) = resultAndReportOfTestCommand()
    json
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
