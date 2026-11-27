package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should
import org.scalatest.mill.tags.Workers
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

import javax.xml.parsers.DocumentBuilderFactory

@Workers
final class ModuleFolderIntegrationSpec
    extends AnyFeatureSpec
    with GivenWhenThen
    with should.Matchers
    with SingleModuleFixture {

  private def makeAlpha1FastCreateFileInWorkingDirectory(build: FixtureBuild, fileName: String): Unit =
    build.replaceInSample(s"${SampleSuite.Alpha1}.scala", SampleTest.Fast.withEmptyBody, SampleTest.Fast.creatingFileInWorkingDirectory(fileName))

  Feature("The module folder") {

    def filesIn(folder: os.Path): Seq[os.Path] = os.walk(folder).filter(os.isFile)

    val leftover = "leftover.txt"

    val sharedLeftoverBuild = sharedMarkingSuites(makeAlpha1FastCreateFileInWorkingDirectory(_, leftover))

    def givenAnEarlierTestCommandLeftTheLeftoverInTheModuleFolder(build: FixtureBuild): Unit = {
      Given(s"Alpha1's test fast creates the file $leftover in its working directory when it runs")
      And(s"the test command has run core.test with no arguments, and $leftover exists in a folder inside the module folder")
      And("the JSON output file has since been deleted")
      val earlierResult = runWritingJsonOutput(build)
      withClue(earlierResult.debugString) {
        filesIn(build.moduleFolder).map(_.last) should contain(leftover)
      }
      os.remove(build.jsonOutputFile)
    }

    def isJUnitXmlReport(file: os.Path): Boolean = {
      val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
      // The default error handler writes each parse error to stderr.
      builder.setErrorHandler(new DefaultHandler)
      // A file that cannot be read fails the scenario; only one that is not XML is not a report.
      try {
        val root = builder.parse(file.toIO).getDocumentElement.getTagName
        root == "testsuites" || root == "testsuite"
      } catch {
        case _: SAXException => false
      }
    }

    def describe(arguments: Seq[String]): String = if (arguments.isEmpty) "no arguments" else arguments.mkString(" ")

    final case class ReportSettingAndArguments(setting: String, arguments: Seq[String])

    val reportSettingsAndArguments = Seq(
      ReportSettingAndArguments("""Some("test-report.xml")""", Nil),
      ReportSettingAndArguments("""Some("junit-results.xml")""", Seq("--", "samples.Alpha*")),
      ReportSettingAndArguments("""Some("test-report.xml")""", Seq("--", "--", "-n", "samples.Fast")),
      ReportSettingAndArguments("""Some("test-report.xml")""", Seq("--", "samples.Gamma*")),
      ReportSettingAndArguments("None", Seq("--", "*", "--", "-l", "samples.Fast"))
    )

    val sharedBuildByReportSetting = reportSettingsAndArguments.map(_.setting).distinct.map { setting =>
      setting -> sharedMarkingSuites { build =>
        build.addToTestModule(s"def testReportXml: T[Option[String]] = Task { $setting }")
        build.replaceInSample(s"${SampleSuite.Beta1}.scala", SampleTest.Slow.withEmptyBody, SampleTest.Slow.failing)
      }
    }.toMap

    for (report <- reportSettingsAndArguments) {
      Scenario(s"With testReportXml ${report.setting} and ${describe(report.arguments)}, the module folder holds no JUnit XML report") {
        Given(s"the module's testReportXml is ${report.setting}")
        And("Beta1's test slow fails")
        When(s"the test command runs core.test, writing JSON output, with ${describe(report.arguments)}")
        sharedBuildByReportSetting(report.setting).use { build =>
          val result = runWritingJsonOutput(build, report.arguments*)

          Then("no file in the module folder or in any folder inside it is an XML document whose root element is testsuites or testsuite")
          withClue(result.debugString) {
            os.isDir(build.moduleFolder) shouldBe true
            filesIn(build.moduleFolder).filter(isJUnitXmlReport) shouldBe empty
          }
        }
      }
    }

    Scenario("A test command whose class globs match no test class deletes the file an earlier test command left in the module folder") {
      sharedLeftoverBuild.use { build =>
        givenAnEarlierTestCommandLeftTheLeftoverInTheModuleFolder(build)
        When("the test command runs core.test, writing JSON output, with -- samples.Gamma*")
        val result = runWritingJsonOutput(build, "--", "samples.Gamma*")

        Then("no file leftover.txt exists in the module folder or in any folder inside it")
        withClue(result.debugString) {
          filesIn(build.moduleFolder).map(_.last) should not contain leftover
        }
      }
    }

    Scenario("A test command that fails on a framework argument leaves the file an earlier test command left in the module folder") {
      sharedLeftoverBuild.use { build =>
        def filesAndContents: Map[os.Path, Seq[Byte]] = filesIn(build.moduleFolder).map(file => file -> os.read.bytes(file).toSeq).toMap
        givenAnEarlierTestCommandLeftTheLeftoverInTheModuleFolder(build)
        val earlierFilesAndContents = filesAndContents
        When("the test command runs core.test, writing JSON output, with -- -- foo")
        val result = runWritingJsonOutput(build, "--", "--", "foo")

        Then("the test command fails with a message naming foo")
        shouldFail(result)
        shouldWriteToTheConsole(result, TestCommandFailure.FrameworkArgumentNotPassedOn("foo").message)

        And("the module folder and every folder inside it hold the files they held before the test command ran, leftover.txt among them, each with the content it had")
        withClue(result.debugString) {
          filesAndContents shouldBe earlierFilesAndContents
        }
      }
    }

    Scenario("The test command leaves Mill's own report from an earlier testForked as it was, and creates no testOnly task folder") {
      Given("mill core.test.testForked has run in the build and written out/core/test/testForked.dest/test-report.xml")
      And("no folder out/core/test/testOnly.dest exists")
      When("the test command runs core.test, writing JSON output, with -- samples.Alpha1")
      withMarkingSuites { build =>
        val millsTestModuleOutFolder = build.workspace / "out" / "core" / "test"
        val millsReport = millsTestModuleOutFolder / "testForked.dest" / "test-report.xml"
        val testOnlyTaskFolder = millsTestModuleOutFolder / "testOnly.dest"
        val testForkedResult = build.eval(Seq("core.test.testForked"))
        withClue(testForkedResult.debugString) {
          os.isFile(millsReport) shouldBe true
          os.exists(testOnlyTaskFolder) shouldBe false
        }
        val reportContent = os.read(millsReport)
        val reportModified = os.mtime(millsReport)
        val result = runWritingJsonOutput(build, "--", "samples.Alpha1")

        withClue(result.debugString) {
          Then("out/core/test/testForked.dest/test-report.xml has the content and modification time it had before the test command ran")
          os.read(millsReport) shouldBe reportContent
          os.mtime(millsReport) shouldBe reportModified

          And("no folder out/core/test/testOnly.dest exists")
          os.exists(testOnlyTaskFolder) shouldBe false
        }
      }
    }

    Scenario("The module folder of a cross module's test module is named as Mill writes the module in a task name") {
      Given("the module is instead the test module of the cross module core at 3.3.8, which Mill names core.3_3_8.test in a task name")
      And("Alpha1's test fast creates the file working-directory.txt in its working directory when it runs")
      When("the test command runs core[3.3.8].test with -- samples.Alpha1")
      withMarkingSuites { build =>
        val workingDirectoryMarker = "working-directory.txt"
        build.replaceInBuild("object core extends ScalaModule {", "object core extends Cross[CoreModule](\"3.3.8\")\n\ntrait CoreModule extends CrossScalaModule {")
        makeAlpha1FastCreateFileInWorkingDirectory(build, workingDirectoryMarker)
        val result = build.runTestCommandWithMillArguments(FixtureBuild.jobsArguments(2))("core[3.3.8].test", "--", "samples.Alpha1")

        Then("the test command succeeds")
        shouldSucceed(result)

        withClue(result.debugString) {
          And("out/org.scalatest.mill.TestCommandModule/modules holds one folder, core.3_3_8.test")
          os.list(build.modulesFolder).map(_.last) shouldBe Seq("core.3_3_8.test")

          And("working-directory.txt exists in the folder sandbox of that folder")
          os.isFile(build.modulesFolder / "core.3_3_8.test" / "sandbox" / workingDirectoryMarker) shouldBe true
        }
      }
    }
  }
}
