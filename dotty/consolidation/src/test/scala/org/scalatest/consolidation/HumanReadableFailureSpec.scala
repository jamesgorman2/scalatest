package org.scalatest.consolidation

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should.Matchers

final class HumanReadableFailureSpec extends AnyFeatureSpec with GivenWhenThen with Matchers with ConsolidatedRuns {

  private def eventLogEndingWith(ending: String): String =
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

  private def eventLogAbortingWith(aborted: String): String =
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
      val eventLog = eventLogEndingWith(
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
      val eventLog = eventLogEndingWith(
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
      val eventLog = eventLogEndingWith(
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
      val eventLog = eventLogEndingWith(
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
      val eventLog = eventLogEndingWith(
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
      val eventLog = eventLogEndingWith(
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
  }

  Feature("A suite's abort sits under its name line") {

    Scenario("A suite aborted with a throwable shows its abort line, then the throwable's lines under it") {
      Given("an event log of a suite that aborts with a `RuntimeException` before its first test")
      val eventLog = eventLogAbortingWith("""suite "SetupFailsSpec": SuiteAborted(throwable: java.lang.RuntimeException "database unreachable")""")

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
      val eventLog = eventLogAbortingWith(
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
      val eventLog = eventLogAbortingWith(
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
}
