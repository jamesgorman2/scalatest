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

  def markersFolder: os.Path = workspace / "markers"

  def moduleFolder: os.Path = workspace / "out" / "org.scalatest.mill.TestCommandModule" / "modules" / "core.test"

  def buildText: String = os.read(buildFile)

  def editBuild(edit: String => String): Unit = os.write.over(buildFile, edit(buildText))

  def replaceInBuild(from: String, to: String): Unit = replaceIn(buildFile, "the build file")(from, to)

  def replaceInSample(fileName: String, from: String, to: String): Unit = replaceIn(samples / fileName, fileName)(from, to)

  private def replaceIn(file: os.Path, description: String)(from: String, to: String): Unit = {
    val text = os.read(file)
    withClue(s"$description holds no $from:\n$text\n") {
      text should include(from)
    }
    os.write.over(file, text.replace(from, to))
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

  def runTestCommandWithMillArguments(millArguments: Seq[String], environment: Map[String, String] = Map.empty)(
      arguments: String*
  ): TestCommandResult =
    TestCommandResult(tester.eval(millArguments ++ testCommandLine(arguments), env = environment))

  def runTestCommandWritingJsonOutput(millArguments: Seq[String] = Nil): TestCommandResult = runTestCommandWritingJsonOutputWith(millArguments)()

  def runTestCommandWritingJsonOutputWith(millArguments: Seq[String], environment: Map[String, String] = Map.empty)(
      arguments: String*
  ): TestCommandResult =
    runTestCommandWithMillArguments(millArguments, environment)(writingJsonOutput(arguments)*)

  def runTestCommandThroughMillBatWritingJsonOutputWith(millArguments: Seq[String])(arguments: String*): TestCommandResult =
    runThroughMillBat((millArguments ++ testCommandLine(writingJsonOutput(arguments)))*)

  private def testCommandLine(arguments: Seq[String]): Seq[String] = "scalatest/test" +: arguments

  private def writingJsonOutput(arguments: Seq[String]): Seq[String] = "core.test" +: "-fJ" +: jsonOutputFile.toString +: arguments

  def runThroughMillBat(millArguments: String*): TestCommandResult = {
    // `mill.bat` runs the Mill it finds in its download folder under the name `MILL_VERSION` gives, so placing the
    // tester's own Mill executable there under that name has `mill.bat` run it without downloading a release.
    val downloadFolder = os.temp.dir()
    os.copy(IntegrationEnvironment.millExecutable, downloadFolder / s"${FixtureBuild.millBatVersion}.bat")
    // The tester's own arguments, such as `--ticker false`, would reach `MILL_PARAMS`, so `mill.bat` is called
    // directly. It leaves `--no-daemon` out of `MILL_PARAMS` where that is the first argument.
    val daemonArguments = if (tester.daemonMode) Nil else Seq("--no-daemon")
    val result = os.call(
      cmd = (IntegrationEnvironment.millBootstrapScript, daemonArguments, millArguments),
      env = tester.millTestSuiteEnv ++ Map("MILL_VERSION" -> FixtureBuild.millBatVersion, "MILL_FINAL_DOWNLOAD_FOLDER" -> downloadFolder.toString),
      cwd = workspace,
      stderr = os.Pipe,
      check = false
    )
    TestCommandResult(IntegrationTester.EvalResult(result))
  }

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

object FixtureBuild {

  def jobsArguments(jobs: Int): Seq[String] = Seq("-j", jobs.toString)

  // The `-jvm` suffix has `mill.bat` run a `.bat` file rather than a native executable.
  private val millBatVersion: String = "1.3.1-jvm"
}
