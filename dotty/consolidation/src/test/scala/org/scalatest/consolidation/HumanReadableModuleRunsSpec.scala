package org.scalatest.consolidation

import org.scalatest.GivenWhenThen
import org.scalatest.events.CapturedStream
import org.scalatest.events.TestRunPart
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should.Matchers

final class HumanReadableModuleRunsSpec extends AnyFeatureSpec with GivenWhenThen with Matchers with ConsolidatedRuns {

  private def stdout(line: String): TestRunPart = TestRunPart.CapturedLine(CapturedStream.Stdout, line)

  private def runEnds(how: String): TestRunPart = TestRunPart.Events(s"run: Run${how.capitalize}\nrun ended $how")

  private def classes(names: String*): Vector[TestClassName] = names.toVector.map(TestClassName(_))

  private final class LibraryCalledDirectly {

    private val output = new OutputSink

    val consolidation: Consolidation = Consolidation.start(output, coloured = false)

    def addRun(forkGroup: Int, workerIndex: Int, parts: TestRunPart*): RunFeed = {
      val feed = addRunInWorker(consolidation, forkGroup, workerIndex, parts*)
      feed.endEventStream()
      feed
    }

    def consolidated(): Vector[String] = {
      consolidation.end()
      output.linesWithoutSummaryLine
    }
  }

  Feature("Runs in the human-readable output") {

    for ((how, line) <- Vector("aborted" -> "*** RUN ABORTED ***", "stopped" -> "*** RUN STOPPED ***")) {
      Scenario(s"A run that aborted or stopped shows its line: a run that $how") {
        Given("the library directly")
        val library = new LibraryCalledDirectly
        And("run 1, fork group 0, worker index 0: suite Beta runs, run ended completed")
        library.addRun(0, 0, runOfSuites("Beta")*)
        And(s"run 2, fork group 0, worker index 1: suite Alpha runs, run ended $how")
        library.addRun(0, 1, runStarts, suiteRuns("Alpha"), runEnds(how))

        When("the library consolidates them")
        val lines = library.consolidated()

        Then("Beta sits under the module's line, then run 2's line holds Alpha and the run's end")
        lines.mkString("\n") shouldBe
          s"""[core.test]
            |  Beta:
            |    - t
            |  Run 2:
            |    Alpha:
            |      - t
            |    $line""".stripExpectedMargin
      }
    }

    Scenario("A run assigned test classes that ran none of them shows its line; a run assigned none that ran none does not show its line: assigned classes that did not run") {
      Given("the library directly")
      val library = new LibraryCalledDirectly
      And("run 1, fork group 0, worker index 0: suite Beta runs, run ended completed")
      library.addRun(0, 0, runOfSuites("Beta")*).addAssignedTestClasses(classes("samples.Beta"))
      And("run 2, fork group 0, worker index 1: run ended completed, with assigned classes samples.Alpha")
      library.addRun(0, 1, runOfSuites()*).addAssignedTestClasses(classes("samples.Alpha"))
      And("run 3, fork group 0, worker index 2: run ended completed, with no assigned class")
      library.addRun(0, 2, runOfSuites()*).addAssignedTestClasses(classes())

      When("the library consolidates them")
      val lines = library.consolidated()

      Then("the output holds the line `  Run 2:` and no line `  Run 1:` or `  Run 3:`")
      lines should contain("  Run 2:")
      lines should contain noneOf ("  Run 1:", "  Run 3:")

      And("the line `  Beta:` sits directly under `[core.test]`")
      lines.take(2) shouldBe Vector("[core.test]", "  Beta:")
    }

    Scenario("The suites of runs that are not shown form one list by name, which lists a suite once for each run it ran in; shown runs follow that list in order of number: three hidden runs and two shown") {
      Given("the library directly")
      val library = new LibraryCalledDirectly
      val runOne = runOfSuites("Zeta", "Alpha")
      val runTwo = Vector(runStarts, suiteRuns("Mid"), stdout("two"), runEndsCompleted)
      val runThree = Vector(runStarts, suiteRuns("Alpha", test = "u"), suiteRuns("Beta"), runEndsCompleted)
      val runTen = Vector(runStarts, suiteRuns("Last"), suiteRuns("Kilo", occurrenceAmongTestsOfItsName = 2), stdout("ten"), runEndsCompleted)

      And("the runs are given to the library in the order 10, 3, 9, 8, 7, 6, 5, 4, 2, 1, so run 3's Alpha starts before run 1's")
      library.addRun(1, 7, runTen*)
      library.addRun(1, 0, runThree*)
      for (workerIndex <- 6 to 1 by -1) {
        library.addRun(1, workerIndex, runOfSuites()*).addAssignedTestClasses(classes())
      }
      library.addRun(0, 1, runTwo*)
      library.addRun(0, 0, runOne*)

      When("the library consolidates them")
      val lines = library.consolidated()

      Then("the hidden runs' suites are one list by name, with run 1's Alpha before run 3's, then Run 2: and Run 10:")
      lines.mkString("\n") shouldBe
        """[core.test]
          |  Alpha:
          |    - t
          |  Alpha:
          |    - u
          |  Beta:
          |    - t
          |  Zeta:
          |    - t
          |  Run 2:
          |    Mid:
          |      - t
          |    [stdout] two
          |  Run 10:
          |    Kilo:
          |      - t
          |    Last:
          |      - t
          |    [stdout] ten""".stripExpectedMargin
    }
  }

  Feature("Assigned test classes") {

    Scenario("A run that ran one of its assigned test classes and not another does not show its line") {
      Given("a run of suite Beta, assigned samples.Beta and samples.Alpha")
      val library = new LibraryCalledDirectly
      library.addRun(0, 0, runOfSuites("Beta")*).addAssignedTestClasses(classes("samples.Beta", "samples.Alpha"))

      When("the library consolidates it")
      val lines = library.consolidated()

      Then("Beta sits directly under the module's line")
      lines shouldBe Vector("[core.test]", "  Beta:", "    - t")
    }

    Scenario("A run never given its assigned test classes, that ran nothing, does not show its line") {
      Given("a run that ran nothing, given no assigned test classes")
      val library = new LibraryCalledDirectly
      library.addRun(0, 0, runOfSuites()*)

      When("the library consolidates it")
      Then("the output holds only the module's line")
      library.consolidated() shouldBe Vector("[core.test]")
    }

    Scenario("Assigned test classes added in more than one call are all the run's assigned test classes") {
      Given("a run of suite Beta, given samples.Alpha, then samples.Beta, as its assigned test classes")
      val library = new LibraryCalledDirectly
      val feed = library.addRun(0, 0, runOfSuites("Beta")*)
      feed.addAssignedTestClasses(classes("samples.Alpha"))
      feed.addAssignedTestClasses(classes("samples.Beta"))

      When("the library consolidates it")
      Then("the run ran one of its assigned test classes, so it does not show its line")
      library.consolidated() shouldBe Vector("[core.test]", "  Beta:", "    - t")
    }

    Scenario("A run that reported a nested suite of an assigned test class ran that class") {
      Given("a run whose suite Outer holds the nested suite samples.Inner, assigned samples.Inner")
      val library = new LibraryCalledDirectly
      val nestedSuites = TestRunPart.Events(
        """
        structure
          suite "Outer", class "samples.Outer"
            suite "Inner", class "samples.Inner"
              test "t", label "- t"
        suite "Outer": SuiteStarting
        suite "Inner": SuiteStarting
        test "t": TestStarting
        test "t": TestSucceeded
        suite "Inner": SuiteCompleted
        suite "Outer": SuiteCompleted
        """
      )
      library.addRun(0, 0, runStarts, nestedSuites, runEndsCompleted).addAssignedTestClasses(classes("samples.Inner"))

      When("the library consolidates it")
      Then("the run does not show its line")
      library.consolidated() shouldBe Vector("[core.test]", "  Outer:", "    Inner:", "      - t")
    }

    Scenario("Assigned test classes cannot be added once the consolidation has ended") {
      Given("a run whose consolidation has ended")
      val library = new LibraryCalledDirectly
      val feed = library.addRun(0, 0, runOfSuites("Beta")*)
      library.consolidated()

      When("the caller adds the run's assigned test classes")
      Then("the library refuses them")
      an[IllegalArgumentException] should be thrownBy feed.addAssignedTestClasses(classes("samples.Beta"))
    }
  }
}
