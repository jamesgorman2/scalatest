package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.Suite
import org.scalatest.matchers.should

trait EveryWorkerFixture extends JsonOutputFixture with SharedFixtureBuilds { this: Suite & should.Matchers & GivenWhenThen =>

  protected def withEveryWorkerBuild[A](test: EveryWorkerBuild => A): A =
    withFixtureBuild(FixtureName.everyWorker)(build => test(new EveryWorkerBuild(build)))

  private def sharedEveryWorkerBuild(setUp: EveryWorkerBuild => Unit): SharedFixtureBuild =
    sharedFixtureBuild(
      FixtureName.everyWorker,
      WorkspaceReset.fixtureFiles(os.sub / "core" / "test" / "src" / "samples").and(WorkspaceReset.noMarkersFolder).and(WorkspaceReset.noModuleFolders)
    )(build => setUp(new EveryWorkerBuild(build)))

  protected val sharedUnchangedEveryWorkerBuild: SharedFixtureBuild = sharedEveryWorkerBuild(_ => ())

  protected val sharedEveryWorkerBuildWithTestParallelism: SharedFixtureBuild = sharedEveryWorkerBuild(_.turnOnTestParallelism())

  protected val sharedEveryWorkerBuildWithBatchFrameworkTasks: SharedFixtureBuild = sharedEveryWorkerBuild(_.turnOnBatchFrameworkTasks())

  protected def inSharedEveryWorkerBuild[A](sharedBuild: SharedFixtureBuild)(test: EveryWorkerBuild => A): A = sharedBuild.use(build => test(new EveryWorkerBuild(build)))

  protected def runs(json: JsonNode): Seq[JsonNode] = json.node(NodeKind.Module, "core.test").contents

  protected def runsOfForkGroup(json: JsonNode, forkGroup: Int): Seq[JsonNode] =
    runs(json).filter(_.forkGroup.contains(forkGroup))

  protected def givenTheModuleHoldsTwoForkGroups(): Unit =
    Given("a module whose first fork group holds suites B1 and B2, and whose second holds A1 and A2")

  protected def shouldRunEachForkGroupInExactlyOneWorker(json: JsonNode): Unit = {
    Then("B1 and B2 each ran once, in the first fork group's run, and A1 and A2 each ran once, in the second's")
    runsOfForkGroup(json, 0).flatMap(_.contentNames) should contain theSameElementsAs Seq("B1", "B2")
    runsOfForkGroup(json, 1).flatMap(_.contentNames) should contain theSameElementsAs Seq("A1", "A2")

    And("each fork group has exactly one run")
    runs(json).map(_.forkGroup) shouldBe Seq(Some(0), Some(1))
  }

  protected def shouldRunEachForkGroupInItsOwnWorkers(json: JsonNode): Unit = {
    Then("B1 and B2 ran only in the first fork group's runs, and A1 and A2 only in the second's")
    runsOfForkGroup(json, 0).flatMap(_.contentNames).distinct should contain theSameElementsAs Seq("B1", "B2")
    runsOfForkGroup(json, 1).flatMap(_.contentNames).distinct should contain theSameElementsAs Seq("A1", "A2")

    And("each fork group has one or two runs")
    runs(json).map(_.forkGroup).toSet shouldBe Set(Some(0), Some(1))
    Seq(0, 1).foreach(forkGroup => runsOfForkGroup(json, forkGroup).size should (be >= 1 and be <= 2))
  }
}

private final class EveryWorkerBuild(fixtureBuild: FixtureBuild) {

  private val defaultForkGrouping: String = """Seq(Seq("samples.B1", "samples.B2"), Seq("samples.A1", "samples.A2"))"""

  def writeSuite(suite: String, testBody: String): Unit =
    os.write.over(
      fixtureBuild.samples / s"$suite.scala",
      s"""package samples
         |
         |import org.scalatest.funsuite.AnyFunSuite
         |
         |final class $suite extends AnyFunSuite {
         |  test("t") {
         |    $testBody
         |  }
         |}
         |""".stripMargin
    )

  def removeSuites(): Unit = os.remove.all(fixtureBuild.samples)

  private val testBodySendingWorkingDirectory: String = """info(System.getProperty("user.dir"))"""

  def emptyMarkersFolder(): os.Path = {
    val markers = fixtureBuild.workspace / "markers"
    os.makeDir(markers)
    markers
  }

  def writeSuiteThatWaitsFor(suite: String, otherSuite: String, markers: os.Path): Unit = {
    val markersLiteral = "\"" + markers.toString.replace('\\', '/') + "\""
    writeSuite(
      suite,
      s"""$testBodySendingWorkingDirectory
         |    val markers = java.nio.file.Paths.get($markersLiteral)
         |    java.nio.file.Files.write(markers.resolve("$suite"), Array.emptyByteArray)
         |    val deadline = System.nanoTime() + 30L * 1000 * 1000 * 1000
         |    while (!java.nio.file.Files.exists(markers.resolve("$otherSuite")) && System.nanoTime() < deadline) {
         |      Thread.sleep(50)
         |    }
         |    assert(java.nio.file.Files.exists(markers.resolve("$otherSuite")), "$otherSuite did not start within 30 seconds")""".stripMargin
    )
  }

  def holdOnlySuitesEachInItsOwnForkGroup(testBodies: (String, String)*): Unit = {
    removeSuites()
    os.makeDir.all(fixtureBuild.samples)
    testBodies.foreach { case (suite, testBody) => writeSuite(suite, testBody) }
    setForkGrouping(testBodies.map { case (suite, _) => s"""Seq("samples.$suite")""" }.mkString("Seq(", ", ", ")"))
  }

  def setForkGrouping(forkGrouping: String): Unit = fixtureBuild.replaceInBuild(defaultForkGrouping, forkGrouping)

  def removeForkGrouping(): Unit = fixtureBuild.replaceInBuild(s"def testForkGrouping = Task { $defaultForkGrouping }", "")

  def turnOnTestParallelism(): Unit = fixtureBuild.replaceInBuild("def testParallelism = false", "def testParallelism = true")

  def turnOffTestSandboxing(): Unit =
    fixtureBuild.replaceInBuild("def testForkGrouping", "def testSandboxWorkingDir = false\n    def testForkGrouping")

  def turnOnBatchFrameworkTasks(): Unit =
    fixtureBuild.replaceInBuild("def testForkGrouping", "def testBatchFrameworkTasks = true\n    def testForkGrouping")

  def resultAndJsonOutputOfTestCommand(): (TestCommandResult, JsonNode) = fixtureBuild.resultAndJsonOutputOfTestCommand()

  def resultAndJsonOutputOfTestCommand(jobs: Int): (TestCommandResult, JsonNode) =
    fixtureBuild.resultAndJsonOutputOfTestCommand(FixtureBuild.jobsArguments(jobs))

  def runTestCommandAsAtTheConsole(): TestCommandResult =
    // With the ticker on, Mill puts its prefix on each line the test command writes.
    fixtureBuild.runTestCommandWritingJsonOutput(FixtureBuild.jobsArguments(2) ++ Seq("--ticker", "true"))

  def workerIndexOf(path: String): Option[Int] =
    Some(os.Path(path))
      .filter(_.startsWith(fixtureBuild.workspace))
      // The workspace can itself sit in a `worker-<i>` folder of the build running these specs.
      .flatMap(_.relativeTo(fixtureBuild.workspace).segments.find(_.matches("worker-\\d+")))
      .map(_.stripPrefix("worker-").toInt)

  def eventLogFolders: Seq[os.Path] = fixtureBuild.eventLogFiles.map(_ / os.up)
}
