package org.scalatest.consolidation

import org.scalatest.GivenWhenThen
import org.scalatest.events.DecodedEvent
import org.scalatest.events.TestEventLogEvent
import org.scalatest.events.TestRunBuilder
import org.scalatest.events.TestRunPart
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should.Matchers

final class HumanReadableFailureSpec extends AnyFeatureSpec with GivenWhenThen with Matchers with ConsolidatedRuns {

  private def eventLogOfTestEndingWith(ending: String): String =
    s"""
    run started
    structure
      suite "StackFlatSpec", class "samples.StackFlatSpec"
        scope "A Stack", label "A Stack"
          test "have size 0", label "- must have size 0"
    suite "StackFlatSpec": SuiteStarting
    scope "A Stack": ScopeOpened
    test "have size 0": TestStarting
    test "have size 0": InfoProvided(message: "checked")
    test "have size 0": NoteProvided(message: "a note")
    test "have size 0": AlertProvided(message: "an alert")
    $ending
    scope "A Stack": ScopeClosed
    suite "StackFlatSpec": SuiteCompleted
    run ended completed
    """

  private def eventLogOfTestEndingThenAbortedBy(ending: String, aborted: String): String =
    s"""
    run started
    structure
      suite "ParAbortSuite", class "samples.ParAbortSuite"
        test "a", label "- a"
    suite "ParAbortSuite": SuiteStarting
    test "a": TestStarting
    $ending
    $aborted
    suite "ParAbortSuite": SuiteCompleted
    run ended completed
    """

  private def eventLogOfSuiteAbortingWith(aborted: String): String =
    s"""
    run started
    structure
      suite "SetupFailsSpec", class "samples.SetupFailsSpec"
        scope "A repo", label "A repo"
          test "read", label "- should read"
          test "write", label "- should write"
    suite "SetupFailsSpec": SuiteStarting
    $aborted
    run ended completed
    """

  Feature("A failure's or cancel's lines sit under its test's line") {

    Scenario("Each frame line sits under its throwable's first line, and a `Cause:` line where that first line sits") {
      Given("an event log of a test that fails with a throwable and a cause, each with a frame")
      val eventLog = eventLogOfTestEndingWith(
        """test "have size 0": TestFailed(throwable: java.lang.IllegalStateException "not empty" frames ["samples.StackFlatSpec.check(StackFlatSpec.scala:9)"] caused by java.io.IOException "closed" frames ["samples.Stack.size(Stack.scala:3)"])"""
      )

      When("the library consolidates it")
      val output = colourMarkedTreeFrom(eventLog)

      Then("the throwable's first line and its `Cause:` line sit under the test's line, each with its frames under it")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}StackFlatSpec:{reset}
          |    {green}A Stack{reset}
          |      {red}- must have size 0 *** FAILED ***{reset}
          |        {red}java.lang.IllegalStateException: not empty{reset}
          |          {red}at samples.StackFlatSpec.check(StackFlatSpec.scala:9){reset}
          |          {red}...{reset}
          |        {red}Cause: java.io.IOException: closed{reset}
          |          {red}at samples.Stack.size(Stack.scala:3){reset}
          |          {red}...{reset}
          |        {green}[info] checked{reset}
          |        {green}[note] a note{reset}
          |        {yellow}[alert] an alert{reset}""".stripExpectedMargin
    }

    Scenario("Each line of a throwable's multi-line message sits where its first line does, even one that starts like a frame line") {
      Given("an event log of a test that fails with a throwable whose message's second line starts with `at `")
      val eventLog = eventLogOfTestEndingWith(
        """test "have size 0": TestFailed(throwable: java.lang.RuntimeException "database unreachable\nat host db-1" frames ["samples.StackFlatSpec.check(StackFlatSpec.scala:9)"])"""
      )

      When("the library consolidates it")
      val output = colourMarkedTreeFrom(eventLog)

      Then("the message's second line sits under the test's line with the throwable's first line, and the frame lines under both")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}StackFlatSpec:{reset}
          |    {green}A Stack{reset}
          |      {red}- must have size 0 *** FAILED ***{reset}
          |        {red}java.lang.RuntimeException: database unreachable{reset}
          |        {red}at host db-1{reset}
          |          {red}at samples.StackFlatSpec.check(StackFlatSpec.scala:9){reset}
          |          {red}...{reset}
          |        {green}[info] checked{reset}
          |        {green}[note] a note{reset}
          |        {yellow}[alert] an alert{reset}""".stripExpectedMargin
    }

    Scenario("A cancel whose throwable is a `StackDepth` shows its message with the failed code's file and line, and no throwable line") {
      Given("an event log of a test cancelled with a `TestCanceledException`")
      val eventLog = eventLogOfTestEndingWith(
        """test "have size 0": TestCanceled(throwable: org.scalatest.exceptions.TestCanceledException "no stack" at StackFlatSpec.scala:7)"""
      )

      When("the library consolidates it")
      val output = colourMarkedTreeFrom(eventLog)

      Then("the message and the failed code's file and line sit under the test's line, in yellow, before its messages")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}StackFlatSpec:{reset}
          |    {green}A Stack{reset}
          |      {yellow}- must have size 0 !!! CANCELED !!!{reset}
          |        {yellow}no stack (StackFlatSpec.scala:7){reset}
          |        {green}[info] checked{reset}
          |        {green}[note] a note{reset}
          |        {yellow}[alert] an alert{reset}""".stripExpectedMargin
    }

    Scenario("A failure whose message differs from its `StackDepth` throwable's shows its own message with the failed code's file and line") {
      Given("an event log of a test that fails with a message of its own and a `TestFailedException` with another")
      val eventLog = eventLogOfTestEndingWith(
        """test "have size 0": TestFailed(message: "Expected an empty stack, but it held 1 item", throwable: org.scalatest.exceptions.TestFailedException "1 did not equal 0" at StackFlatSpec.scala:9)"""
      )

      When("the library consolidates it")
      val output = colourMarkedTreeFrom(eventLog)

      Then("the failure's own message and the failed code's file and line sit under the test's line, and the throwable's message shows nowhere")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}StackFlatSpec:{reset}
          |    {green}A Stack{reset}
          |      {red}- must have size 0 *** FAILED ***{reset}
          |        {red}Expected an empty stack, but it held 1 item (StackFlatSpec.scala:9){reset}
          |        {green}[info] checked{reset}
          |        {green}[note] a note{reset}
          |        {yellow}[alert] an alert{reset}""".stripExpectedMargin
    }

    Scenario("A failure whose message differs from that of a throwable that is not a `StackDepth` shows the throwable's first line, as ScalaTest 3.2.20 does") {
      Given("an event log of a test that fails with a message of its own and an `IllegalStateException` with another")
      val eventLog = eventLogOfTestEndingWith(
        """test "have size 0": TestFailed(message: "Expected an empty stack", throwable: java.lang.IllegalStateException "not empty" frames ["samples.StackFlatSpec.check(StackFlatSpec.scala:9)"])"""
      )

      When("the library consolidates it")
      val output = colourMarkedTreeFrom(eventLog)

      Then("the throwable's first line and frames sit under the test's line, and the failure's own message shows nowhere")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}StackFlatSpec:{reset}
          |    {green}A Stack{reset}
          |      {red}- must have size 0 *** FAILED ***{reset}
          |        {red}java.lang.IllegalStateException: not empty{reset}
          |          {red}at samples.StackFlatSpec.check(StackFlatSpec.scala:9){reset}
          |          {red}...{reset}
          |        {green}[info] checked{reset}
          |        {green}[note] a note{reset}
          |        {yellow}[alert] an alert{reset}""".stripExpectedMargin
    }

    Scenario("A failure's analysis follows its message, after an `Analysis:` line") {
      Given("an event log of a test that fails with a `TestFailedException` and an analysis")
      val eventLog = eventLogOfTestEndingWith(
        """test "have size 0": TestFailed(throwable: org.scalatest.exceptions.TestFailedException "Stack(1) did not equal Stack(0)" at StackFlatSpec.scala:9, analysis: ["Stack(size: 1 -> 0)"])"""
      )

      When("the library consolidates it")
      val output = colourMarkedTreeFrom(eventLog)

      Then("the message, `Analysis:` and the analysis sit under the test's line, before its messages")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}StackFlatSpec:{reset}
          |    {green}A Stack{reset}
          |      {red}- must have size 0 *** FAILED ***{reset}
          |        {red}Stack(1) did not equal Stack(0) (StackFlatSpec.scala:9){reset}
          |        {red}Analysis:{reset}
          |        {red}Stack(size: 1 -> 0){reset}
          |        {green}[info] checked{reset}
          |        {green}[note] a note{reset}
          |        {yellow}[alert] an alert{reset}""".stripExpectedMargin
    }

    Scenario("A test aborted by its DistributedTestRunnerSuite shows its abort line, then the throwable's lines under it") {
      Given("an event log of a test that holds only the SuiteAborted of the DistributedTestRunnerSuite running it")
      val eventLog =
        """
        run started
        structure
          suite "ParAbortSuite", class "samples.ParAbortSuite"
            test "a", label "- a"
        suite "ParAbortSuite": SuiteStarting
        test "a": SuiteAborted(message: "Exception encountered when invoking run on a nested suite - no fixture", throwable: java.lang.RuntimeException "no fixture")
        suite "ParAbortSuite": SuiteCompleted
        run ended completed
        """

      When("the library consolidates it")
      val output = colourMarkedTreeFrom(eventLog)

      Then("the test's abort line sits under the suite's name line, with the throwable's lines under it, in red")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}ParAbortSuite:{reset}
          |    {red}- a *** ABORTED ***{reset}
          |      {red}java.lang.RuntimeException: no fixture{reset}
          |        {red}...{reset}""".stripExpectedMargin
    }

    Scenario("A test that succeeded, then was aborted by its DistributedTestRunnerSuite, shows its abort line in place of its success") {
      Given("an event log of a test that succeeds, then holds the SuiteAborted of the DistributedTestRunnerSuite running it")
      val eventLog = eventLogOfTestEndingThenAbortedBy(
        """test "a": TestSucceeded""",
        """test "a": SuiteAborted(message: "no teardown", throwable: java.lang.RuntimeException "no teardown")"""
      )

      When("the library consolidates it")
      val output = colourMarkedTreeFrom(eventLog)

      Then("the test's abort line, with the throwable's lines under it, replaces its success line")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}ParAbortSuite:{reset}
          |    {red}- a *** ABORTED ***{reset}
          |      {red}java.lang.RuntimeException: no teardown{reset}
          |        {red}...{reset}""".stripExpectedMargin
    }

    Scenario("A test that failed, then was aborted by its DistributedTestRunnerSuite, shows its abort line in place of its failure") {
      Given("an event log of a test that fails, then holds the SuiteAborted of the DistributedTestRunnerSuite running it")
      val eventLog = eventLogOfTestEndingThenAbortedBy(
        """test "a": TestFailed(message: "2 did not equal 3")""",
        """test "a": SuiteAborted(message: "no teardown", throwable: java.lang.RuntimeException "no teardown")"""
      )

      When("the library consolidates it")
      val output = colourMarkedTreeFrom(eventLog)

      Then("the test's abort line, with the throwable's lines under it, replaces its failure lines")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}ParAbortSuite:{reset}
          |    {red}- a *** ABORTED ***{reset}
          |      {red}java.lang.RuntimeException: no teardown{reset}
          |        {red}...{reset}""".stripExpectedMargin
    }
  }

  Feature("A suite's abort sits under its name line") {

    Scenario("A suite aborted with a throwable shows its abort line, then the throwable's lines under it") {
      Given("an event log of a suite that aborts with a `RuntimeException` before its first test")
      val eventLog = eventLogOfSuiteAbortingWith("""suite "SetupFailsSpec": SuiteAborted(throwable: java.lang.RuntimeException "database unreachable")""")

      When("the library consolidates it")
      val output = colourMarkedTreeFrom(eventLog)

      Then("the abort line and the throwable's lines sit under the suite's name line, in red, then the count of what did not run")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}SetupFailsSpec:{reset}
          |    {red}samples.SetupFailsSpec *** ABORTED ***{reset}
          |      {red}java.lang.RuntimeException: database unreachable{reset}
          |        {red}...{reset}
          |    {red}(1 scope, 2 tests not run){reset}""".stripExpectedMargin
    }

    Scenario("A suite aborted with a `StackDepth` throwable shows its message with the failed code's file and line") {
      Given("an event log of a suite that aborts with a message and a `TestFailedException`")
      val eventLog = eventLogOfSuiteAbortingWith(
        """suite "SetupFailsSpec": SuiteAborted(message: "Exception encountered when invoking run on a nested suite - database unreachable", throwable: org.scalatest.exceptions.TestFailedException "database unreachable" at SetupFailsSpec.scala:5)"""
      )

      When("the library consolidates it")
      val output = colourMarkedTreeFrom(eventLog)

      Then("the message and the failed code's file and line sit under the abort line, with no throwable line")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}SetupFailsSpec:{reset}
          |    {red}samples.SetupFailsSpec *** ABORTED ***{reset}
          |      {red}Exception encountered when invoking run on a nested suite - database unreachable (SetupFailsSpec.scala:5){reset}
          |    {red}(1 scope, 2 tests not run){reset}""".stripExpectedMargin
    }

    Scenario("A suite aborted with a message that differs from that of a throwable that is not a `StackDepth` shows the throwable's lines, as ScalaTest 3.2.20 does") {
      Given("an event log of a suite that aborts with a message of its own and a `RuntimeException` with another")
      val eventLog = eventLogOfSuiteAbortingWith(
        """suite "SetupFailsSpec": SuiteAborted(message: "Exception encountered when attempting to run a suite with class name: samples.SetupFailsSpec", throwable: java.lang.RuntimeException "database unreachable")"""
      )

      When("the library consolidates it")
      val output = colourMarkedTreeFrom(eventLog)

      Then("the abort line and the throwable's lines sit under the suite's name line, and the abort's own message shows nowhere")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}SetupFailsSpec:{reset}
          |    {red}samples.SetupFailsSpec *** ABORTED ***{reset}
          |      {red}java.lang.RuntimeException: database unreachable{reset}
          |        {red}...{reset}
          |    {red}(1 scope, 2 tests not run){reset}""".stripExpectedMargin
    }

    Scenario("A suite that aborted after some of its children ran shows its abort line after them") {
      Given("an event log of a suite that aborts after its first test, before its nested suite starts")
      val eventLog =
        """
        run started
        structure
          suite "PartlyRunSpec", class "samples.PartlyRunSpec"
            test "ran", label "- ran"
            suite "InnerSpec", class "samples.InnerSpec"
              test "inner", label "- inner"
        suite "PartlyRunSpec": SuiteStarting
        test "ran": TestStarting
        test "ran": TestSucceeded
        suite "PartlyRunSpec": SuiteAborted(throwable: java.lang.RuntimeException "database unreachable")
        run ended completed
        """

      When("the library consolidates it")
      val output = colourMarkedTreeFrom(eventLog)

      Then("the nested suite shows `*** NOT RUN ***` under its name line, and the outer suite's abort follows its children")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}PartlyRunSpec:{reset}
          |    {green}- ran{reset}
          |    {green}InnerSpec:{reset}
          |      {red}samples.InnerSpec *** NOT RUN ***{reset}
          |      {red}(1 test not run){reset}
          |    {red}samples.PartlyRunSpec *** ABORTED ***{reset}
          |      {red}java.lang.RuntimeException: database unreachable{reset}
          |        {red}...{reset}""".stripExpectedMargin
    }
  }

  Feature("An event with an empty message and a throwable with no message") {

    def eventLogOfTwoTestsWhoseFirstEndsWith(ending: String): String =
      s"""
      run started
      structure
        suite "SilentSpec", class "samples.SilentSpec"
          test "first", label "- first"
          test "second", label "- second"
      suite "SilentSpec": SuiteStarting
      test "first": TestStarting
      $ending
      test "second": TestStarting
      test "second": InfoProvided(message: "still checked")
      test "second": TestSucceeded
      suite "SilentSpec": SuiteCompleted
      run ended completed
      """

    Scenario("A failed test shows its throwable's class and frames, and every other test's output still shows") {
      Given("an event log of a test that fails with an empty message and a throwable with no message, then a test that succeeds")
      val eventLog = eventLogOfTwoTestsWhoseFirstEndsWith(
        """test "first": TestFailed(message: "", throwable: java.lang.IllegalStateException frames ["samples.SilentSpec.check(SilentSpec.scala:9)"])"""
      )

      When("the library consolidates it")
      val output = colourMarkedTreeFrom(eventLog)

      Then("the failure shows the throwable's class and frames, and the second test and its message follow")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}SilentSpec:{reset}
          |    {red}- first *** FAILED ***{reset}
          |      {red}java.lang.IllegalStateException:{reset}
          |        {red}at samples.SilentSpec.check(SilentSpec.scala:9){reset}
          |        {red}...{reset}
          |    {green}- second{reset}
          |      {green}[info] still checked{reset}""".stripExpectedMargin
    }

    Scenario("A failed test whose throwable is its own cause shows the throwable once") {
      Given("an event log of a test that fails with an empty message and a throwable with no message that is its own cause")
      val events = TestRunBuilder.build(TestRunPart.Events(eventLogOfTwoTestsWhoseFirstEndsWith(
        """test "first": TestFailed(message: "", throwable: java.lang.IllegalStateException frames ["samples.SilentSpec.check(SilentSpec.scala:9)"])"""
      ))).fold(fail(_), _.events.map(_.event))
      events.collect { case TestEventLogEvent.ScalaTestEvent(_, failed: DecodedEvent.TestFailed) => failed.throwable }.flatten
        .foreach(throwable => throwable.link(Some(throwable), Vector.empty))

      When("the library consolidates it")
      val output = new OutputSink
      val consolidation = Consolidation.start(output, coloured = true)
      val feed = consolidation.addRun(moduleLabel)
      events.foreach(event => feed.add(RunEvent.of(event).fold(fail(_), identity)))
      feed.endEventStream()
      consolidation.end()

      Then("the failure shows the throwable's class and frames once, with no `Cause:` line, and the second test follows")
      output.colourMarkedTree shouldBe
        """{cyan}[core.test]{reset}
          |  {green}SilentSpec:{reset}
          |    {red}- first *** FAILED ***{reset}
          |      {red}java.lang.IllegalStateException:{reset}
          |        {red}at samples.SilentSpec.check(SilentSpec.scala:9){reset}
          |        {red}...{reset}
          |    {green}- second{reset}
          |      {green}[info] still checked{reset}""".stripExpectedMargin
    }

    Scenario("A canceled test shows its throwable's class and frames, and every other test's output still shows") {
      Given("an event log of a test canceled with an empty message and a throwable with no message, then a test that succeeds")
      val eventLog = eventLogOfTwoTestsWhoseFirstEndsWith(
        """test "first": TestCanceled(message: "", throwable: java.lang.IllegalStateException frames ["samples.SilentSpec.check(SilentSpec.scala:9)"])"""
      )

      When("the library consolidates it")
      val output = colourMarkedTreeFrom(eventLog)

      Then("the cancel shows the throwable's class and frames, and the second test and its message follow")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}SilentSpec:{reset}
          |    {yellow}- first !!! CANCELED !!!{reset}
          |      {yellow}java.lang.IllegalStateException:{reset}
          |        {yellow}at samples.SilentSpec.check(SilentSpec.scala:9){reset}
          |        {yellow}...{reset}
          |    {green}- second{reset}
          |      {green}[info] still checked{reset}""".stripExpectedMargin
    }

    Scenario("An aborted suite shows its throwable's class, and every other suite's output still shows") {
      Given("an event log of a suite that aborts with an empty message and a throwable with no message, then a suite whose test succeeds")
      val eventLog =
        """
        run started
        structure
          suite "SilentSpec", class "samples.SilentSpec"
            test "first", label "- first"
        suite "SilentSpec": SuiteStarting
        suite "SilentSpec": SuiteAborted(message: "", throwable: java.lang.IllegalStateException)
        structure
          suite "LaterSpec", class "samples.LaterSpec"
            test "later", label "- later"
        suite "LaterSpec": SuiteStarting
        test "later": TestStarting
        test "later": InfoProvided(message: "still checked")
        test "later": TestSucceeded
        suite "LaterSpec": SuiteCompleted
        run ended completed
        """

      When("the library consolidates it")
      val output = colourMarkedTreeFrom(eventLog)

      Then("the abort shows the throwable's class, and the other suite, its test and its message show too")
      output shouldBe
        """{cyan}[core.test]{reset}
          |  {green}LaterSpec:{reset}
          |    {green}- later{reset}
          |      {green}[info] still checked{reset}
          |  {green}SilentSpec:{reset}
          |    {red}samples.SilentSpec *** ABORTED ***{reset}
          |      {red}java.lang.IllegalStateException:{reset}
          |        {red}...{reset}
          |    {red}(1 test not run){reset}""".stripExpectedMargin
    }

    Scenario("An aborted run shows its throwable's class, and every other run's output still shows") {
      Given("an event log of a run that aborts with an empty message and a throwable with no message, and another run whose test succeeds")
      val abortedRun =
        """
        run started
        run: RunAborted(message: "", throwable: java.lang.OutOfMemoryError)
        run ended aborted
        """
      val laterRun =
        """
        run started
        structure
          suite "LaterSpec", class "samples.LaterSpec"
            test "later", label "- later"
        suite "LaterSpec": SuiteStarting
        test "later": TestStarting
        test "later": InfoProvided(message: "still checked")
        test "later": TestSucceeded
        suite "LaterSpec": SuiteCompleted
        run ended completed
        """

      When("the library consolidates both runs")
      val output = new OutputSink
      val consolidation = Consolidation.start(output, coloured = true)
      addRun(consolidation, TestRunPart.Events(abortedRun)).endEventStream()
      addRun(consolidation, TestRunPart.Events(laterRun)).endEventStream()
      consolidation.end()

      Then("the run's abort shows the throwable's class, and the later run's suite, test and message follow")
      output.colourMarkedTree shouldBe
        """{cyan}[core.test]{reset}
          |  {cyan}Run 1:{reset}
          |    {red}*** RUN ABORTED ***{reset}
          |      {red}java.lang.OutOfMemoryError:{reset}
          |        {red}...{reset}
          |  {green}LaterSpec:{reset}
          |    {green}- later{reset}
          |      {green}[info] still checked{reset}""".stripExpectedMargin
    }
  }
}
