package org.scalatest.mill

import mill.testkit.IntegrationTester

final class FixtureBuild(tester: IntegrationTester) {

  import org.scalatest.matchers.should.Matchers.{include, should, shouldBe, withClue}

  private val buildFile: os.Path = tester.workspacePath / "build.mill"

  private val testModuleDependencies: String = """    def mvnDeps = Seq(mvn"com.edc::scalatest::3.2.20")"""

  def workspace: os.Path = tester.workspacePath

  def testSources: os.Path = workspace / "core" / "test" / "src"

  def samples: os.Path = testSources / "samples"

  def jsonOutputFile: os.Path = workspace / "report.json"

  def testRunMarkers: os.Path = workspace / "markers"

  def buildText: String = os.read(buildFile)

  def editBuild(edit: String => String): Unit = os.write.over(buildFile, edit(buildText))

  def replaceInBuild(from: String, to: String): Unit =
    editBuild { build =>
      withClue(s"the build file holds no $from:\n$build\n") {
        build should include(from)
      }
      build.replace(from, to)
    }

  def addPlugin(): Unit =
    editBuild { build =>
      require(
        !build.linesIterator.exists(isHeaderMvnDepsLine),
        s"the build already holds a header line //| mvnDeps:\n$build"
      )
      s"${IntegrationEnvironment.pluginHeaderLine}\n$build"
    }

  def removePlugin(): Unit = removeBuildLines("header line //| mvnDeps:")(isHeaderMvnDepsLine)

  private def isHeaderMvnDepsLine(line: String): Boolean = line.startsWith("//| mvnDeps:")

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

  def runTestCommandWithTicker(arguments: String*): TestCommandResult =
    // The tester turns Mill's ticker off, and with it the prefix Mill puts on each line a task writes.
    runTestCommandWithMillArguments(Seq("--ticker", "true"))(arguments*)

  def runTestCommandWithTickerAndColour(arguments: String*): TestCommandResult =
    runTestCommandWithMillArguments(Seq("--ticker", "true", "--color", "true"))(arguments*)

  def runTestCommandWithMillArguments(millArguments: Seq[String])(arguments: String*): TestCommandResult =
    TestCommandResult(tester.eval(millArguments ++ ("scalatest/test" +: arguments)))

  def runTestCommandWritingJsonOutput(millArguments: Seq[String] = Nil): TestCommandResult = runTestCommandWritingJsonOutputWith(millArguments)()

  def runTestCommandWritingJsonOutputWith(millArguments: Seq[String])(arguments: String*): TestCommandResult =
    runTestCommandWithMillArguments(millArguments)(("core.test" +: "-fJ" +: jsonOutputFile.toString +: arguments)*)

  def jsonOutput: JsonNode = JsonNode.read(jsonOutputFile)

  def resultAndJsonOutputOfTestCommand(millArguments: Seq[String] = Nil): (TestCommandResult, JsonNode) = {
    os.remove(jsonOutputFile)
    val result = runTestCommandWritingJsonOutput(millArguments)
    withClue(result.debugString) {
      os.exists(jsonOutputFile) shouldBe true
    }
    (result, jsonOutput)
  }

  def jsonOutputOfTestCommand(): JsonNode = {
    val (_, json) = resultAndJsonOutputOfTestCommand()
    json
  }

  def anyTestWorkerStarted: Boolean =
    // Mill writes a `testargs` file for each worker it starts.
    os.exists(workspace / "out") && os.walk(workspace / "out").exists(_.last == "testargs")

  def eventLogFiles: Seq[os.Path] = os.walk(workspace / "out").filter(_.last == JvmTestModuleRun.eventLogFileName)

  def compileBuildFile(): Unit = {
    // `resolve _` compiles the build, so a later command's output holds no compile messages.
    val result = tester.eval(Seq("resolve", "_"))
    require(result.isSuccess, s"the build did not compile:\n${result.debugString}")
  }

  def removeModuleOutput(): Unit = os.remove.all(workspace / "out" / "core")

  def removeProcessIdFile(): Unit = tester.removeProcessIdFile()

  def resultWithBspNotifications[A](stderrLog: os.Path)(use: BspClientSession => A): WithNotifications[A] =
    BspClientSession.resultWithNotifications(tester, IntegrationEnvironment.millExecutable, stderrLog)(use)
}
