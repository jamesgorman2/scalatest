package org.scalatest.events

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import EventNotationBlocks.dedentedAndJoined

final class TestRunBuilderSpec extends AnyWordSpec with Matchers {

  private val stackSuite =
    """
    structure
      suite "StackSuite", class "samples.StackSuite"
        scope "A Stack", label "A Stack"
          test "pops", label "- pops"
    """

  private val stackSuiteId = id(suiteAt("samples.StackSuite"))
  private val aStackId = id(suiteAt("samples.StackSuite"), scopeAt("A Stack"))
  private val popsId = id(suiteAt("samples.StackSuite"), testAt("A Stack pops"))

  private def built(parts: TestRunPart*): BuiltRun = {
    TestRunBuilder.build(parts*) match {
      case Right(run) => run
      case Left(reason) => fail(s"the run was not built: $reason")
    }
  }

  private def eventsOf(notation: String): Vector[TestEventLogEvent] = built(TestRunPart.Events(notation)).events.map(_.event)

  private def rejection(notation: String): Option[String] = TestRunBuilder.build(TestRunPart.Events(notation)).left.toOption

  private def structuresOf(notation: String): Vector[IndexedSeq[StructureNode]] =
    eventsOf(notation).collect { case TestEventLogEvent.Structure(nodes) => nodes }

  private def executionsOf(notation: String): Vector[Option[NodeExecution]] =
    eventsOf(notation).collect { case TestEventLogEvent.ScalaTestEvent(execution, _) => execution }

  private def lastDecodedEventOf(notation: String): DecodedEvent = {
    eventsOf(notation).last match {
      case TestEventLogEvent.ScalaTestEvent(_, decoded) => decoded
      case other => fail(s"the last event is not a ScalaTest event: $other")
    }
  }

  private def lastParentExecutionOf(notation: String): Option[Int] = executionsOf(notation).last.flatMap(_.executionPath.init.lastOption)

  private def throwableOf(written: String): DecodedThrowable = {
    lastDecodedEventOf(s"run: RunAborted(throwable: $written)") match {
      case DecodedEvent.RunAborted(_, Some(throwable), _, _, _, _, _) => throwable
      case other => fail(s"the event has no throwable: $other")
    }
  }

  private def id(places: NodePlace*): NodeId = NodeId(places.toVector)

  private def suiteAt(suiteId: String, index: Int = 0): NodePlace = NodePlace.Suite(suiteId, index)

  private def scopeAt(text: String, index: Int = 0): NodePlace = NodePlace.Scope(text, index)

  private def testAt(testName: String): NodePlace = NodePlace.Test(testName)

  private def under(parent: NodeId, places: NodePlace*): NodeId = NodeId(parent.places ++ places)

  private def suite(id: NodeId, parent: Option[NodeId], position: Option[Int], name: String, suiteId: String, className: String): StructureNode =
    StructureNode.Suite(id, parent, position, name, suiteId, className)

  private def scope(id: NodeId, parent: NodeId, position: Int, name: String, label: String): StructureNode =
    StructureNode.Scope(id, parent, position, name, label)

  private def test(id: NodeId, parent: NodeId, position: Int, name: String, label: String): StructureNode =
    StructureNode.Test(id, parent, position, name, label)

  private def ignoredTest(id: NodeId, parent: NodeId, position: Int, name: String, label: String): StructureNode =
    StructureNode.Test(id, parent, position, name, label, ignored = true)

  private def execution(node: NodeId, executionPath: Int*): Option[NodeExecution] = Some(NodeExecution(node, executionPath.toVector))

  "The test run builder" when {

    "given events and captured lines" should {
      "emit every event and captured line in the order given" in {
        val run = built(
          TestRunPart.Events("run started"),
          TestRunPart.CapturedLine(CapturedStream.Stderr, "warn: about to divide"),
          TestRunPart.Events(
            """
            structure
              suite "DivideSuite", class "samples.DivideSuite"
            suite "DivideSuite": SuiteStarting
            """
          ),
          TestRunPart.CapturedLine(CapturedStream.Stdout, "dividing now"),
          TestRunPart.Events("run ended completed")
        )
        val divideSuiteId = id(suiteAt("samples.DivideSuite"))

        run.items shouldBe Vector(
          BuiltRunItem.Event(BuiltEvent("run started", TestEventLogEvent.RunStarted(1L, "1970-01-01T00:00:00Z"), WrittenParts.none)),
          BuiltRunItem.CapturedLine(CapturedStream.Stderr, "warn: about to divide"),
          BuiltRunItem.Event(
            BuiltEvent(
              "structure\n  suite \"DivideSuite\", class \"samples.DivideSuite\"",
              TestEventLogEvent.Structure(Vector(suite(divideSuiteId, None, None, "DivideSuite", "samples.DivideSuite", "samples.DivideSuite"))),
              WrittenParts.none
            )
          ),
          BuiltRunItem.Event(
            BuiltEvent(
              "suite \"DivideSuite\": SuiteStarting",
              TestEventLogEvent.ScalaTestEvent(execution(divideSuiteId, 1), DecodedEvent.SuiteStarting(None, "main", 0L)),
              WrittenParts.none
            )
          ),
          BuiltRunItem.CapturedLine(CapturedStream.Stdout, "dividing now"),
          BuiltRunItem.Event(BuiltEvent("run ended completed", TestEventLogEvent.RunEnded(RunOutcome.Completed), WrittenParts.none))
        )
      }

      "resolve a node that an earlier part's structure holds" in {
        val run = built(TestRunPart.Events(stackSuite), TestRunPart.CapturedLine(CapturedStream.Stdout, "popping"), TestRunPart.Events("""test "pops": TestStarting"""))

        run.events.last.event shouldBe TestEventLogEvent.ScalaTestEvent(execution(popsId, 1, 1, 1), DecodedEvent.TestStarting(None, "main", 0L))
      }

      "build no event from notation that holds none" in {
        built(TestRunPart.Events("\n   \n")).items shouldBe Vector.empty
      }
    }

    "given how a run ended" should {
      "build the runEnded event of a run that completed" in {
        eventsOf("run ended completed") shouldBe Vector(TestEventLogEvent.RunEnded(RunOutcome.Completed))
      }

      "build the runEnded event of a run that was aborted" in {
        eventsOf("run ended aborted") shouldBe Vector(TestEventLogEvent.RunEnded(RunOutcome.Aborted))
      }

      "build the runEnded event of a run that was stopped" in {
        eventsOf("run ended stopped") shouldBe Vector(TestEventLogEvent.RunEnded(RunOutcome.Stopped))
      }

      "reject any other ending" in {
        rejection("run ended failed") shouldBe Some("a run ends completed, aborted or stopped, not 'failed'")
      }
    }

    "given a suite's first structure" should {
      "mark ignored each test whose line ends ignored, and no other test" in {
        structuresOf(
          """
          structure
            suite "StackSuite", class "samples.StackSuite"
              test "pops", label "- pops", ignored
              test "peeks", label "- peeks", test name "stack peeks", ignored
              test "pushes", label "- pushes"
          """
        ) shouldBe Vector(
          Vector(
            suite(stackSuiteId, None, None, "StackSuite", "samples.StackSuite", "samples.StackSuite"),
            ignoredTest(under(stackSuiteId, testAt("pops")), stackSuiteId, 0, "pops", "- pops"),
            ignoredTest(under(stackSuiteId, testAt("stack peeks")), stackSuiteId, 1, "peeks", "- peeks"),
            test(under(stackSuiteId, testAt("pushes")), stackSuiteId, 2, "pushes", "- pushes")
          )
        )
      }
      "give each node its node path, its parent's node path and its declared position among its siblings" in {
        val outer = id(suiteAt("samples.OuterSuite"))
        val aStack = under(outer, scopeAt("A Stack"))
        val whenEmpty = under(aStack, scopeAt("when empty"))
        val inner = under(outer, suiteAt("inner"))

        structuresOf(
          """
          structure
            suite "OuterSuite", class "samples.OuterSuite"
              scope "A Stack", label "A Stack"
                test "pops", label "- pops"
                scope "when empty", label "when empty"
                  test "is empty", label "- is empty"
              test "has a top", label "- has a top"
              suite "InnerSuite", class "samples.InnerSuite", id "inner"
                test "inner one", label "- inner one"
          """
        ) shouldBe Vector(
          Vector(
            suite(outer, None, None, "OuterSuite", "samples.OuterSuite", "samples.OuterSuite"),
            scope(aStack, outer, 0, "A Stack", "A Stack"),
            test(under(outer, testAt("A Stack pops")), aStack, 0, "pops", "- pops"),
            scope(whenEmpty, aStack, 1, "when empty", "when empty"),
            test(under(outer, testAt("A Stack when empty is empty")), whenEmpty, 0, "is empty", "- is empty"),
            test(under(outer, testAt("has a top")), outer, 1, "has a top", "- has a top"),
            suite(inner, Some(outer), Some(2), "InnerSuite", "inner", "samples.InnerSuite"),
            test(under(inner, testAt("inner one")), inner, 0, "inner one", "- inner one")
          )
        )
      }

      "index the sibling suites that share a suite ID, and the sibling scopes that share a name, in declared order" in {
        val outer = id(suiteAt("samples.TwiceSuites"))

        structuresOf(
          """
          structure
            suite "TwiceSuites", class "samples.TwiceSuites"
              scope "A Stack", label "A Stack"
              suite "InnerSuite", class "samples.InnerSuite"
              scope "A Stack", label "A Stack"
              suite "InnerSuite", class "samples.InnerSuite"
          """
        ) shouldBe Vector(
          Vector(
            suite(outer, None, None, "TwiceSuites", "samples.TwiceSuites", "samples.TwiceSuites"),
            scope(under(outer, scopeAt("A Stack", 0)), outer, 0, "A Stack", "A Stack"),
            suite(under(outer, suiteAt("samples.InnerSuite", 0)), Some(outer), Some(1), "InnerSuite", "samples.InnerSuite", "samples.InnerSuite"),
            scope(under(outer, scopeAt("A Stack", 1)), outer, 2, "A Stack", "A Stack"),
            suite(under(outer, suiteAt("samples.InnerSuite", 1)), Some(outer), Some(3), "InnerSuite", "samples.InnerSuite", "samples.InnerSuite")
          )
        )
      }

      "give a suite with another suite ID its own node path" in {
        val inA = id(suiteAt("a.SameSuite"))
        val inB = id(suiteAt("b.SameSuite"))

        structuresOf(
          """
          structure
            suite "SameSuite", class "a.SameSuite"
              test "in a", label "- in a"
          structure
            suite "SameSuite", class "b.SameSuite"
              test "in b", label "- in b"
          """
        ) shouldBe Vector(
          Vector(suite(inA, None, None, "SameSuite", "a.SameSuite", "a.SameSuite"), test(under(inA, testAt("in a")), inA, 0, "in a", "- in a")),
          Vector(suite(inB, None, None, "SameSuite", "b.SameSuite", "b.SameSuite"), test(under(inB, testAt("in b")), inB, 0, "in b", "- in b"))
        )
      }

      "give its own node path to a suite whose suite ID an earlier structure holds only under a parent" in {
        val helper = id(suiteAt("samples.HelperSuite"))

        structuresOf(
          """
          structure
            suite "OuterSuite", class "samples.OuterSuite"
              suite "HelperSuite", class "samples.HelperSuite"
                test "helps", label "- helps"
          structure
            suite "HelperSuite", class "samples.HelperSuite"
              test "helps", label "- helps"
          """
        ).last shouldBe Vector(
          suite(helper, None, None, "HelperSuite", "samples.HelperSuite", "samples.HelperSuite"),
          test(under(helper, testAt("helps")), helper, 0, "helps", "- helps")
        )
      }

      "add a suite after the siblings that share its suite ID where its ordinal names no suite an earlier structure holds" in {
        structuresOf(
          """
          structure
            suite "StackSuite", class "samples.StackSuite"
          structure
            suite "StackSuite" #2, class "samples.StackSuite"
          """
        ).last shouldBe Vector(suite(id(suiteAt("samples.StackSuite", 1)), None, None, "StackSuite", "samples.StackSuite", "samples.StackSuite"))
      }

      "reject an ordinal on a new suite other than the next among the suites that share its name" in {
        rejection(
          """
          structure
            suite "StackSuite", class "samples.StackSuite"
          structure
            suite "StackSuite" #3, class "samples.StackSuite"
          """
        ) shouldBe Some(
          "a suite new to the event log is #2 among the suites that share its name, not #3: 'suite \"StackSuite\" #3, class \"samples.StackSuite\"'"
        )
      }

      "reject an ordinal that names a suite of another suite ID" in {
        rejection(
          """
          structure
            suite "SameSuite", class "a.SameSuite"
          structure
            suite "SameSuite" #1, class "b.SameSuite"
          """
        ) shouldBe Some(
          "suite \"SameSuite\" #1 is not a suite of suite ID b.SameSuite under the structure's parent: 'suite \"SameSuite\" #1, class \"b.SameSuite\"'"
        )
      }

      "reject a structure that holds no suite" in {
        rejection("structure") shouldBe Some("a structure starts with its suite, indented 2 spaces:\nstructure")
      }

      "reject a structure that starts with a scope" in {
        rejection(
          """
          structure
            scope "A Stack", label "A Stack"
          """
        ) shouldBe Some("a structure starts with its suite, indented 2 spaces:\nstructure\n  scope \"A Stack\", label \"A Stack\"")
      }

      "reject a second tree in one structure" in {
        rejection(
          """
          structure
            suite "OneSuite", class "samples.OneSuite"
            suite "TwoSuite", class "samples.TwoSuite"
          """
        ) shouldBe Some("'suite \"TwoSuite\", class \"samples.TwoSuite\"' is not indented 2 spaces under a suite or scope of its structure")
      }

      "reject a node under a test" in {
        rejection(
          """
          structure
            suite "StackSuite", class "samples.StackSuite"
              test "pops", label "- pops"
                test "pushes", label "- pushes"
          """
        ) shouldBe Some("'test \"pushes\", label \"- pushes\"' is not indented 2 spaces under a suite or scope of its structure")
      }

      "reject a suite under a scope" in {
        rejection(
          """
          structure
            suite "OuterSuite", class "samples.OuterSuite"
              scope "A Stack", label "A Stack"
                suite "InnerSuite", class "samples.InnerSuite"
          """
        ) shouldBe Some("a suite is nested in a suite, not in a scope: 'suite \"InnerSuite\", class \"samples.InnerSuite\"'")
      }

      "reject a node indented by an odd number of spaces" in {
        rejection(
          """
          structure
            suite "StackSuite", class "samples.StackSuite"
               test "pops", label "- pops"
          """
        ) shouldBe Some("'test \"pops\", label \"- pops\"' is not indented by a multiple of 2 spaces under its structure")
      }

      "reject a line indented under an event" in {
        rejection(
          """
          run started
            suite "StackSuite", class "samples.StackSuite"
          """
        ) shouldBe Some("'suite \"StackSuite\", class \"samples.StackSuite\"' is indented under a line that is not a structure")
      }

      "reject a node that is no suite, scope or test" in {
        rejection(
          """
          structure
            spec "StackSuite", class "samples.StackSuite"
          """
        ) shouldBe Some("expected suite, scope or test at column 1 of 'spec \"StackSuite\", class \"samples.StackSuite\"'")
      }

      "reject two tests of one suite that have one test name" in {
        rejection(
          """
          structure
            suite "StackSuite", class "samples.StackSuite"
              test "A Stack pops", label "- A Stack pops"
              scope "A Stack", label "A Stack"
                test "pops", label "- pops"
          """
        ) shouldBe Some("two tests of one suite have the test name \"A Stack pops\": give 'test \"pops\", label \"- pops\"' its own test name")
      }
    }

    "given a structure under a suite" should {
      "give a suite new to the run its parent's node path and no position" in {
        val outer = id(suiteAt("samples.OuterSuite"))
        val helper = under(outer, suiteAt("samples.HelperSuite"))
        val aHelper = under(helper, scopeAt("A helper"))

        structuresOf(
          """
          structure
            suite "OuterSuite", class "samples.OuterSuite"
              test "uses a helper", label "- uses a helper"
          structure under suite "OuterSuite"
            suite "HelperSuite", class "samples.HelperSuite"
              scope "A helper", label "A helper"
                test "helps", label "- helps"
          """
        ).last shouldBe Vector(
          suite(helper, Some(outer), None, "HelperSuite", "samples.HelperSuite", "samples.HelperSuite"),
          scope(aHelper, helper, 0, "A helper", "A helper"),
          test(under(helper, testAt("A helper helps")), aHelper, 0, "helps", "- helps")
        )
      }

      "put the suite under the suite an ordinal picks among those sharing the name" in {
        val userTwo = id(suiteAt("samples.UserSuites"), suiteAt("samples.two.UserSuite"))

        structuresOf(
          """
          structure
            suite "UserSuites", class "samples.UserSuites"
              suite "UserSuite", class "samples.one.UserSuite"
              suite "UserSuite", class "samples.two.UserSuite"
          structure under suite "UserSuite" #2
            suite "HelperSuite", class "samples.HelperSuite"
          """
        ).last shouldBe Vector(
          suite(under(userTwo, suiteAt("samples.HelperSuite")), Some(userTwo), None, "HelperSuite", "samples.HelperSuite", "samples.HelperSuite")
        )
      }

      "give its own node path to a suite whose suite ID an earlier structure holds only with no parent" in {
        val outer = id(suiteAt("samples.OuterSuite"))
        val helper = under(outer, suiteAt("samples.HelperSuite"))

        structuresOf(
          """
          structure
            suite "HelperSuite", class "samples.HelperSuite"
              test "helps", label "- helps"
          structure
            suite "OuterSuite", class "samples.OuterSuite"
          structure under suite "OuterSuite"
            suite "HelperSuite", class "samples.HelperSuite"
              test "helps", label "- helps"
          """
        ).last shouldBe Vector(
          suite(helper, Some(outer), None, "HelperSuite", "samples.HelperSuite", "samples.HelperSuite"),
          test(under(helper, testAt("helps")), helper, 0, "helps", "- helps")
        )
      }

      "give its own node path to a suite whose suite ID an earlier structure holds only under another parent" in {
        val userTwo = id(suiteAt("samples.UserSuites"), suiteAt("samples.User2Suite"))
        val helper = under(userTwo, suiteAt("samples.HelperSuite"))

        structuresOf(
          """
          structure
            suite "UserSuites", class "samples.UserSuites"
              suite "User1Suite", class "samples.User1Suite"
              suite "User2Suite", class "samples.User2Suite"
          structure under suite "User1Suite"
            suite "HelperSuite", class "samples.HelperSuite"
              test "helps", label "- helps"
          structure under suite "User2Suite"
            suite "HelperSuite", class "samples.HelperSuite"
              test "helps", label "- helps"
          """
        ).last shouldBe Vector(
          suite(helper, Some(userTwo), None, "HelperSuite", "samples.HelperSuite", "samples.HelperSuite"),
          test(under(helper, testAt("helps")), helper, 0, "helps", "- helps")
        )
      }

      "reject a structure under a scope" in {
        rejection(dedentedAndJoined(stackSuite, """
          structure under scope "A Stack"
            suite "HelperSuite", class "samples.HelperSuite"
          """)) shouldBe Some("a structure is under a suite or a test at column 32 of 'structure under scope \"A Stack\"'")
      }

      "reject a structure under a suite no structure holds" in {
        rejection(
          """
          structure under suite "OuterSuite"
            suite "HelperSuite", class "samples.HelperSuite"
          """
        ) shouldBe Some("no structure so far holds suite \"OuterSuite\"")
      }
    }

    "given a structure under a test" should {
      "give a suite new to the run the test's node path as its parent and no position" in {
        val usesAHelper = id(suiteAt("samples.OuterSuite"), testAt("uses a helper"))
        val helper = under(usesAHelper, suiteAt("samples.HelperSuite"))

        structuresOf(
          """
          structure
            suite "OuterSuite", class "samples.OuterSuite"
              test "uses a helper", label "- uses a helper"
          structure under test "uses a helper"
            suite "HelperSuite", class "samples.HelperSuite"
              test "helps", label "- helps"
          """
        ).last shouldBe Vector(
          suite(helper, Some(usesAHelper), None, "HelperSuite", "samples.HelperSuite", "samples.HelperSuite"),
          test(under(helper, testAt("helps")), helper, 0, "helps", "- helps")
        )
      }

      "give the suites that two tests run a node path each" in {
        val outer = id(suiteAt("samples.OuterSuite"))

        structuresOf(
          """
          structure
            suite "OuterSuite", class "samples.OuterSuite"
              test "first", label "- first"
              test "second", label "- second"
          structure under test "first"
            suite "HelperSuite", class "samples.HelperSuite"
          structure under test "second"
            suite "HelperSuite", class "samples.HelperSuite"
          """
        ).drop(1).map(_.head) shouldBe Vector(
          suite(under(outer, testAt("first"), suiteAt("samples.HelperSuite")), Some(under(outer, testAt("first"))), None, "HelperSuite", "samples.HelperSuite", "samples.HelperSuite"),
          suite(under(outer, testAt("second"), suiteAt("samples.HelperSuite")), Some(under(outer, testAt("second"))), None, "HelperSuite", "samples.HelperSuite", "samples.HelperSuite")
        )
      }
    }

    "given a further structure for a suite an earlier structure holds" should {
      "keep the node path of a test of the same test name, whichever parent it now has" in {
        val stackSpec = id(suiteAt("samples.StackSpec"))
        val aStack = under(stackSpec, scopeAt("A Stack"))

        structuresOf(
          """
          structure
            suite "StackSpec", class "samples.DelegatingSuite", id "samples.StackSpec"
              test "A Stack pops", label "- A Stack pops"
          structure
            suite "StackSpec", class "samples.DelegatingSuite", id "samples.StackSpec"
              scope "A Stack", label "A Stack"
                test "pops", label "- pops"
                test "pushes", label "- pushes"
          """
        ).last shouldBe Vector(
          suite(stackSpec, None, None, "StackSpec", "samples.StackSpec", "samples.DelegatingSuite"),
          scope(aStack, stackSpec, 0, "A Stack", "A Stack"),
          test(under(stackSpec, testAt("A Stack pops")), aStack, 0, "pops", "- pops"),
          test(under(stackSpec, testAt("A Stack pushes")), aStack, 1, "pushes", "- pushes")
        )
      }

      "give a test the node path of its written test name" in {
        structuresOf(
          """
          structure
            suite "StackSuite", class "samples.StackSuite"
              test "pops", label "- pops", test name "stack pops"
          structure
            suite "StackSuite", class "samples.StackSuite"
              test "pops", label "- pops"
              test "removes", label "- removes", test name "stack pops"
          """
        ).last shouldBe Vector(
          suite(stackSuiteId, None, None, "StackSuite", "samples.StackSuite", "samples.StackSuite"),
          test(under(stackSuiteId, testAt("pops")), stackSuiteId, 0, "pops", "- pops"),
          test(under(stackSuiteId, testAt("stack pops")), stackSuiteId, 1, "removes", "- removes")
        )
      }

      "keep the node path of a scope of the same name at the same place among its parent's scopes of that name" in {
        val dupSpec = id(suiteAt("samples.DupSpec"))

        structuresOf(
          """
          structure
            suite "DupSpec", class "samples.DupSpec"
              scope "A Stack", label "A Stack"
                test "is empty", label "- is empty"
              scope "A Stack", label "A Stack"
                test "pops", label "- pops"
          structure
            suite "DupSpec", class "samples.DupSpec"
              scope "A Queue", label "A Queue"
              scope "A Stack", label "A Stack"
              scope "A Stack", label "A Stack"
                test "pops", label "- pops"
              scope "A Stack", label "A Stack"
          """
        ).last shouldBe Vector(
          suite(dupSpec, None, None, "DupSpec", "samples.DupSpec", "samples.DupSpec"),
          scope(under(dupSpec, scopeAt("A Queue")), dupSpec, 0, "A Queue", "A Queue"),
          scope(under(dupSpec, scopeAt("A Stack", 0)), dupSpec, 1, "A Stack", "A Stack"),
          scope(under(dupSpec, scopeAt("A Stack", 1)), dupSpec, 2, "A Stack", "A Stack"),
          test(under(dupSpec, testAt("A Stack pops")), under(dupSpec, scopeAt("A Stack", 1)), 0, "pops", "- pops"),
          scope(under(dupSpec, scopeAt("A Stack", 2)), dupSpec, 3, "A Stack", "A Stack")
        )
      }

      "give a scope of an earlier scope's name under another parent its own node path" in {
        val stackSpec = id(suiteAt("samples.StackSpec"))
        val aQueue = under(stackSpec, scopeAt("A Queue"))

        structuresOf(
          """
          structure
            suite "StackSpec", class "samples.StackSpec"
              scope "A Stack", label "A Stack"
                scope "when empty", label "when empty"
          structure
            suite "StackSpec", class "samples.StackSpec"
              scope "A Queue", label "A Queue"
                scope "when empty", label "when empty"
          """
        ).last shouldBe Vector(
          suite(stackSpec, None, None, "StackSpec", "samples.StackSpec", "samples.StackSpec"),
          scope(aQueue, stackSpec, 0, "A Queue", "A Queue"),
          scope(under(aQueue, scopeAt("when empty")), aQueue, 0, "when empty", "when empty")
        )
      }

      "give a nested suite its parent's node path and the position its first structure gave it" in {
        val outer = id(suiteAt("samples.OuterSuite"))
        val inner = under(outer, suiteAt("samples.InnerSuite"))

        structuresOf(
          """
          structure
            suite "OuterSuite", class "samples.OuterSuite"
              test "outer one", label "- outer one"
              suite "InnerSuite", class "samples.InnerSuite"
                test "inner one", label "- inner one"
          structure under suite "OuterSuite"
            suite "InnerSuite", class "samples.InnerSuite"
              test "inner two", label "- inner two"
              test "inner one", label "- inner one"
          """
        ).last shouldBe Vector(
          suite(inner, Some(outer), Some(1), "InnerSuite", "samples.InnerSuite", "samples.InnerSuite"),
          test(under(inner, testAt("inner two")), inner, 0, "inner two", "- inner two"),
          test(under(inner, testAt("inner one")), inner, 1, "inner one", "- inner one")
        )
      }

      "give a suite added by a structure under a suite its parent's node path and no position" in {
        val outer = id(suiteAt("samples.OuterSuite"))
        val helper = under(outer, suiteAt("samples.HelperSuite"))

        structuresOf(
          """
          structure
            suite "OuterSuite", class "samples.OuterSuite"
          structure under suite "OuterSuite"
            suite "HelperSuite", class "samples.HelperSuite"
          structure under suite "OuterSuite"
            suite "HelperSuite", class "samples.HelperSuite"
              test "helps", label "- helps"
          """
        ).last shouldBe Vector(
          suite(helper, Some(outer), None, "HelperSuite", "samples.HelperSuite", "samples.HelperSuite"),
          test(under(helper, testAt("helps")), helper, 0, "helps", "- helps")
        )
      }

      "continue the first of the sibling suites that share the suite ID where the suite line gives no ordinal" in {
        val outer = id(suiteAt("samples.TwiceSuites"))
        val first = under(outer, suiteAt("samples.InnerSuite", 0))

        structuresOf(
          """
          structure
            suite "TwiceSuites", class "samples.TwiceSuites"
              suite "InnerSuite", class "samples.InnerSuite"
                test "inner one", label "- inner one"
              suite "InnerSuite", class "samples.InnerSuite"
                test "inner one", label "- inner one"
          structure under suite "TwiceSuites"
            suite "InnerSuite", class "samples.InnerSuite"
              test "inner two", label "- inner two"
              test "inner one", label "- inner one"
          """
        ).last shouldBe Vector(
          suite(first, Some(outer), Some(0), "InnerSuite", "samples.InnerSuite", "samples.InnerSuite"),
          test(under(first, testAt("inner two")), first, 0, "inner two", "- inner two"),
          test(under(first, testAt("inner one")), first, 1, "inner one", "- inner one")
        )
      }

      "continue the sibling suite that the suite line's ordinal names" in {
        val outer = id(suiteAt("samples.TwiceSuites"))
        val second = under(outer, suiteAt("samples.InnerSuite", 1))

        structuresOf(
          """
          structure
            suite "TwiceSuites", class "samples.TwiceSuites"
              suite "InnerSuite", class "samples.InnerSuite"
                test "inner one", label "- inner one"
              suite "InnerSuite", class "samples.InnerSuite"
                test "inner one", label "- inner one"
          structure under suite "TwiceSuites"
            suite "InnerSuite" #2, class "samples.InnerSuite"
              test "inner two", label "- inner two"
          """
        ).last shouldBe Vector(
          suite(second, Some(outer), Some(1), "InnerSuite", "samples.InnerSuite", "samples.InnerSuite"),
          test(under(second, testAt("inner two")), second, 0, "inner two", "- inner two")
        )
      }

      "add a sibling suite of the same suite ID where the suite line's ordinal names no suite an earlier structure holds" in {
        val outer = id(suiteAt("samples.OuterSuites"))
        val second = under(outer, suiteAt("samples.Inner1Suite", 1))

        structuresOf(
          """
          structure
            suite "OuterSuites", class "samples.OuterSuites"
              suite "Inner1Suite", class "samples.Inner1Suite"
                test "inner one", label "- inner one"
          structure under suite "OuterSuites"
            suite "Inner1Suite" #2, class "samples.Inner1Suite"
              test "inner one", label "- inner one"
          """
        ).last shouldBe Vector(
          suite(second, Some(outer), None, "Inner1Suite", "samples.Inner1Suite", "samples.Inner1Suite"),
          test(under(second, testAt("inner one")), second, 0, "inner one", "- inner one")
        )
      }

      "reject a nested suite, which keeps the structure and place it has" in {
        rejection(
          """
          structure
            suite "OuterSuite", class "samples.OuterSuite"
          structure
            suite "OuterSuite", class "samples.OuterSuite"
              suite "InnerSuite", class "samples.InnerSuite"
          """
        ) shouldBe Some("a further structure holds its suite's scopes and tests only, not 'suite \"InnerSuite\", class \"samples.InnerSuite\"'")
      }

      "reject another class for the suite" in {
        rejection(
          """
          structure
            suite "StackSpec", class "samples.StackSpec", id "stack"
          structure
            suite "StackSpec", class "samples.OtherSpec", id "stack"
          """
        ) shouldBe Some(
          "a further structure keeps its suite's class, samples.StackSpec:\nstructure\n  suite \"StackSpec\", class \"samples.OtherSpec\", id \"stack\""
        )
      }
    }

    "given an event that names a node" should {
      "assign the event to the node of that kind and name" in {
        val pops = id(suiteAt("samples.pops"))

        executionsOf(
          """
          structure
            suite "pops", class "samples.pops"
              scope "pops", label "pops"
                test "pops", label "- pops"
          test "pops": TestStarting
          scope "pops": ScopeOpened
          suite "pops": SuiteStarting
          """
        ) shouldBe Vector(
          execution(under(pops, testAt("pops pops")), 1, 1, 1),
          execution(under(pops, scopeAt("pops")), 1, 1),
          execution(pops, 1)
        )
      }

      "assign the event to the node its ordinal picks among those sharing a name, in the order structures first hold them" in {
        val outer = id(suiteAt("samples.TwiceSuites"))
        val first = under(outer, suiteAt("samples.InnerSuite", 0))
        val second = under(outer, suiteAt("samples.InnerSuite", 1))
        val other = id(suiteAt("samples.other.InnerSuite"))

        executionsOf(
          """
          structure
            suite "TwiceSuites", class "samples.TwiceSuites"
              suite "InnerSuite", class "samples.InnerSuite"
                test "inner one", label "- inner one"
              suite "InnerSuite", class "samples.InnerSuite"
                test "inner one", label "- inner one"
          test "inner one" #1: TestStarting
          structure
            suite "InnerSuite", class "samples.other.InnerSuite"
              test "inner one", label "- inner one"
          test "inner one" #3: TestStarting
          suite "InnerSuite" #2: SuiteStarting
          test "inner one" #2: TestStarting
          suite "InnerSuite" #3: SuiteStarting
          """
        ) shouldBe Vector(
          execution(under(first, testAt("inner one")), 1, 1, 1),
          execution(under(other, testAt("inner one")), 1, 1),
          execution(second, 1, 1),
          execution(under(second, testAt("inner one")), 1, 1, 1),
          execution(other, 1)
        )
      }

      "name a node as the latest structure names it" in {
        val stackSpec = id(suiteAt("samples.StackSpec"))
        val aStackPops = under(stackSpec, testAt("A Stack pops"))

        executionsOf(
          """
          structure
            suite "StackSpec", class "samples.StackSpec"
              test "A Stack pops", label "- A Stack pops"
          test "A Stack pops": TestStarting
          structure
            suite "StackSpec", class "samples.StackSpec"
              scope "A Stack", label "A Stack"
                test "pops", label "- pops"
          test "pops": TestSucceeded
          """
        ) shouldBe Vector(execution(aStackPops, 1, 1), execution(aStackPops, 1, 1, 1))
      }

      "reject the name an earlier structure gave a node that a later one renamed" in {
        rejection(
          """
          structure
            suite "StackSpec", class "samples.StackSpec"
              test "A Stack pops", label "- A Stack pops"
          structure
            suite "StackSpec", class "samples.StackSpec"
              scope "A Stack", label "A Stack"
                test "pops", label "- pops"
          test "A Stack pops": TestSucceeded
          """
        ) shouldBe Some("no structure so far holds test \"A Stack pops\"")
      }

      "assign an event of the run to no node" in {
        eventsOf("""run: AlertProvided(message: "slow")""") shouldBe Vector(
          TestEventLogEvent.ScalaTestEvent(None, DecodedEvent.AlertProvided("slow", None, None, "main", 0L))
        )
      }

      "reject a node that only a later structure holds" in {
        rejection(dedentedAndJoined("""test "pops": TestStarting""", stackSuite)) shouldBe Some("no structure so far holds test \"pops\"")
      }

      "reject a node of another kind than the one that has the name" in {
        rejection(dedentedAndJoined(stackSuite, """scope "pops": ScopeOpened""")) shouldBe Some("no structure so far holds scope \"pops\"")
      }

      "reject a name that several nodes share where the event gives no ordinal" in {
        rejection(
          """
          structure
            suite "DupSpec", class "samples.DupSpec"
              scope "A Stack", label "A Stack"
              scope "A Stack", label "A Stack"
          scope "A Stack": ScopeOpened
          """
        ) shouldBe Some("the structures so far hold 2 nodes named scope \"A Stack\": add #<n>")
      }

      "reject an ordinal beyond the nodes that share the name" in {
        rejection(dedentedAndJoined(stackSuite, """test "pops" #2: TestStarting""")) shouldBe Some("no structure so far holds test \"pops\" #2: 1 share that name")
      }

      "reject an execution for an event of the run" in {
        rejection("run execution 2: RunStopped") shouldBe Some("an event assigned to the run has no execution: 'run execution 2: RunStopped'")
      }

      "reject an ancestor's execution for an event of the run" in {
        rejection(dedentedAndJoined(stackSuite, """run, in suite "StackSuite" execution 1: RunStopped""")) shouldBe
          Some("an event assigned to the run has no execution: 'run, in suite \"StackSuite\" execution 1: RunStopped'")
      }

      "reject a line that names no run, suite, scope or test" in {
        rejection("""spec "pops": TestStarting""") shouldBe Some("expected run, suite, scope or test at column 1 of 'spec \"pops\": TestStarting'")
      }

      "reject a line that names no event class" in {
        rejection("""run: (message: "lost")""") shouldBe Some("expected an event class at column 6 of 'run: (message: \"lost\")'")
      }

      "reject text after the event" in {
        rejection("run: RunStopped and more") shouldBe Some("unexpected text at column 16 of 'run: RunStopped and more'")
      }
    }

    "indexing an event's executions" should {
      "give the execution of each node from the top-level suite down to the event's node, the test's scopes included" in {
        executionsOf(dedentedAndJoined(stackSuite, """test "pops": TestStarting""")) shouldBe Vector(execution(popsId, 1, 1, 1))
      }

      "give the execution the event writes" in {
        executionsOf(dedentedAndJoined(stackSuite, """test "pops" execution 4: TestStarting""")) shouldBe Vector(execution(popsId, 1, 1, 4))
      }

      "give a nested suite's event the execution of the suite above it" in {
        val outer = id(suiteAt("samples.OuterSuite"))

        executionsOf(
          """
          structure
            suite "OuterSuite", class "samples.OuterSuite"
              suite "InnerSuite", class "samples.InnerSuite"
          suite "OuterSuite" execution 2: SuiteStarting
          suite "InnerSuite": SuiteStarting
          """
        ) shouldBe Vector(execution(outer, 2), execution(under(outer, suiteAt("samples.InnerSuite")), 2, 1))
      }

      "give the event of a suite a test runs the execution of that test" in {
        val flaky = id(suiteAt("samples.RetrySuite"), testAt("flaky"))

        executionsOf(
          """
          structure
            suite "RetrySuite", class "samples.RetrySuite"
              test "flaky", label "- flaky"
          structure under test "flaky"
            suite "HelperSuite", class "samples.HelperSuite"
          test "flaky": TestStarting
          test "flaky": TestFailed
          test "flaky" execution 2: TestStarting
          suite "HelperSuite": SuiteStarting
          """
        ).last shouldBe execution(under(flaky, suiteAt("samples.HelperSuite")), 1, 2, 1)
      }

      "give the execution of each ancestor the event marks" in {
        executionsOf(
          dedentedAndJoined(stackSuite, """
          scope "A Stack", in suite "StackSuite" execution 5: ScopeOpened
          test "pops" execution 2, in suite "StackSuite" execution 4, in scope "A Stack" execution 3: TestStarting
          """)
        ) shouldBe Vector(execution(aStackId, 5, 1), execution(popsId, 4, 3, 2))
      }

      "reject a marked node that is not above the event's node" in {
        rejection(dedentedAndJoined(stackSuite, """suite "StackSuite", in scope "A Stack" execution 1: SuiteStarting""")) shouldBe
          Some("scope \"A Stack\" is not above suite \"StackSuite\": 'suite \"StackSuite\", in scope \"A Stack\" execution 1: SuiteStarting'")
      }

      "reject ancestors marked other than from the top-level suite down" in {
        rejection(dedentedAndJoined(stackSuite, """test "pops", in scope "A Stack" execution 1, in suite "StackSuite" execution 1: TestStarting""")) shouldBe
          Some(
            "an event marks each ancestor's execution once, from the top-level suite down: 'test \"pops\", in scope \"A Stack\" execution 1, in suite \"StackSuite\" execution 1: TestStarting'"
          )
      }

      "reject an ancestor marked twice" in {
        rejection(dedentedAndJoined(stackSuite, """test "pops", in scope "A Stack" execution 1, in scope "A Stack" execution 2: TestStarting""")) shouldBe
          Some(
            "an event marks each ancestor's execution once, from the top-level suite down: 'test \"pops\", in scope \"A Stack\" execution 1, in scope \"A Stack\" execution 2: TestStarting'"
          )
      }

      "reject an execution below 1" in {
        rejection(dedentedAndJoined(stackSuite, """test "pops" execution 0: TestStarting""")) shouldBe
          Some("expected a number from 1 at column 23 of 'test \"pops\" execution 0: TestStarting'")
      }

      "reject an ancestor's execution below 1" in {
        rejection(dedentedAndJoined(stackSuite, """test "pops", in scope "A Stack" execution 0: TestStarting""")) shouldBe
          Some("expected a number from 1 at column 43 of 'test \"pops\", in scope \"A Stack\" execution 0: TestStarting'")
      }

      "reject an execution that does not fit an Int" in {
        rejection(dedentedAndJoined(stackSuite, """test "pops" execution 2147483648: TestStarting""")) shouldBe
          Some("'2147483648' does not fit an Int at column 23 of 'test \"pops\" execution 2147483648: TestStarting'")
      }
    }

    "defaulting an ancestor's execution" should {
      "take the ancestor's latest execution that is open" in {
        lastParentExecutionOf(
          dedentedAndJoined(stackSuite, """
          scope "A Stack": ScopeOpened
          scope "A Stack" execution 2: ScopeOpened
          scope "A Stack" execution 3: ScopeOpened
          scope "A Stack" execution 3: ScopeClosed
          test "pops": TestStarting
          """)
        ) shouldBe Some(2)
      }

      "take the ancestor's latest execution where none is open" in {
        lastParentExecutionOf(
          dedentedAndJoined(stackSuite, """
          scope "A Stack": ScopeOpened
          scope "A Stack" execution 2: ScopeOpened
          scope "A Stack" execution 2: ScopeClosed
          scope "A Stack": ScopeClosed
          test "pops": TestStarting
          """)
        ) shouldBe Some(2)
      }

      "take the ancestor's first execution where the ancestor has none" in {
        lastParentExecutionOf(dedentedAndJoined(stackSuite, """test "pops" execution 2: TestStarting""")) shouldBe Some(1)
      }

      "count an execution as open from its first event, whatever that event is" in {
        lastParentExecutionOf(
          dedentedAndJoined(stackSuite, """
          scope "A Stack" execution 2: ScopeClosed
          scope "A Stack": InfoProvided(message: "checked")
          test "pops": TestStarting
          """)
        ) shouldBe Some(1)
      }

      "count an execution as open from the first event that marks it" in {
        lastParentExecutionOf(
          dedentedAndJoined(stackSuite, """
          scope "A Stack": ScopeOpened
          test "pops", in scope "A Stack" execution 2: TestStarting
          scope "A Stack" execution 3: ScopeOpened
          scope "A Stack" execution 3: ScopeClosed
          test "pops": TestSucceeded
          """)
        ) shouldBe Some(2)
      }

      "count a scope's execution as closed by ScopePending" in {
        lastParentExecutionOf(
          dedentedAndJoined(stackSuite, """
          scope "A Stack": ScopeOpened
          scope "A Stack" execution 2: ScopeOpened
          scope "A Stack" execution 2: ScopePending
          test "pops": TestStarting
          """)
        ) shouldBe Some(1)
      }

      "count a suite's execution as closed by SuiteCompleted" in {
        lastParentExecutionOf(
          dedentedAndJoined(stackSuite, """
          suite "StackSuite": SuiteStarting
          suite "StackSuite" execution 2: SuiteStarting
          suite "StackSuite" execution 2: SuiteCompleted
          scope "A Stack": ScopeOpened
          """)
        ) shouldBe Some(1)
      }

      "count a suite's execution as closed by SuiteAborted" in {
        lastParentExecutionOf(
          dedentedAndJoined(stackSuite, """
          suite "StackSuite": SuiteStarting
          suite "StackSuite" execution 2: SuiteStarting
          suite "StackSuite" execution 2: SuiteAborted(throwable: java.lang.RuntimeException "boom")
          scope "A Stack": ScopeOpened
          """)
        ) shouldBe Some(1)
      }

      "count a test's execution as closed by its closing event" in {
        val closings = Vector(
          "TestSucceeded",
          "TestFailed",
          "TestCanceled",
          "TestPending",
          "TestIgnored"
        )

        closings.map { closing =>
          closing -> lastParentExecutionOf(
            s"""
            structure
              suite "RetrySuite", class "samples.RetrySuite"
                test "flaky", label "- flaky"
            structure under test "flaky"
              suite "HelperSuite", class "samples.HelperSuite"
            test "flaky": TestStarting
            test "flaky" execution 2: TestStarting
            test "flaky" execution 2: $closing
            suite "HelperSuite": SuiteStarting
            """
          )
        } shouldBe closings.map(_ -> Some(1))
      }

      "take the execution of the parent the latest structure gives the node" in {
        lastParentExecutionOf(
          """
          structure
            suite "StackSpec", class "samples.StackSpec"
              test "A Stack pops", label "- A Stack pops"
          suite "StackSpec" execution 3: SuiteStarting
          structure
            suite "StackSpec", class "samples.StackSpec"
              scope "A Stack", label "A Stack"
                test "pops", label "- pops"
          scope "A Stack" execution 2: ScopeOpened
          test "pops": TestStarting
          """
        ) shouldBe Some(2)
      }
    }

    "given a ScalaTest event that writes no field" should {
      "build each event class with no optional field, an empty message and empty lists" in {
        val expectedByClassName = Vector(
          "TestStarting" -> DecodedEvent.TestStarting(None, "main", 0L),
          "TestSucceeded" -> DecodedEvent.TestSucceeded(None, None, "main", 0L),
          "TestFailed" -> DecodedEvent.TestFailed("", Vector.empty, None, None, None, "main", 0L),
          "TestIgnored" -> DecodedEvent.TestIgnored(None, "main", 0L),
          "TestPending" -> DecodedEvent.TestPending(None, None, "main", 0L),
          "TestCanceled" -> DecodedEvent.TestCanceled("", None, None, None, "main", 0L),
          "SuiteStarting" -> DecodedEvent.SuiteStarting(None, "main", 0L),
          "SuiteCompleted" -> DecodedEvent.SuiteCompleted(None, None, "main", 0L),
          "SuiteAborted" -> DecodedEvent.SuiteAborted("", None, None, None, "main", 0L),
          "RunStarting" -> DecodedEvent.RunStarting(0, Map.empty, None, "main", 0L),
          "RunCompleted" -> DecodedEvent.RunCompleted(None, None, None, "main", 0L),
          "RunStopped" -> DecodedEvent.RunStopped(None, None, None, "main", 0L),
          "RunAborted" -> DecodedEvent.RunAborted("", None, None, None, None, "main", 0L),
          "InfoProvided" -> DecodedEvent.InfoProvided("", None, None, "main", 0L),
          "AlertProvided" -> DecodedEvent.AlertProvided("", None, None, "main", 0L),
          "NoteProvided" -> DecodedEvent.NoteProvided("", None, None, "main", 0L),
          "MarkupProvided" -> DecodedEvent.MarkupProvided("", None, "main", 0L),
          "ScopeOpened" -> DecodedEvent.ScopeOpened(None, "main", 0L),
          "ScopeClosed" -> DecodedEvent.ScopeClosed(None, "main", 0L),
          "ScopePending" -> DecodedEvent.ScopePending(None, "main", 0L),
          "DiscoveryStarting" -> DecodedEvent.DiscoveryStarting("main", 0L),
          "DiscoveryCompleted" -> DecodedEvent.DiscoveryCompleted(None, "main", 0L)
        )

        expectedByClassName.map { case (className, _) => className -> lastDecodedEventOf(s"run: $className") } shouldBe expectedByClassName
      }

      "reject a class the test event log does not carry" in {
        rejection("run: TestExploded") shouldBe Some("the test event log has no event class TestExploded: 'run: TestExploded'")
      }
    }

    "given a ScalaTest event that writes fields" should {
      "build a failed test's fields" in {
        lastDecodedEventOf(
          """run: TestFailed(message: "1 was not 0", analysis: ["Stack(size: 1 -> 0)", "second"], duration: 12, location: LineInFile(7, "FailSuite.scala", None), threadName: "worker-3", timeStamp: 1700000000000)"""
        ) shouldBe DecodedEvent.TestFailed(
          "1 was not 0",
          Vector("Stack(size: 1 -> 0)", "second"),
          None,
          Some(12L),
          Some(LineInFile(7, "FailSuite.scala", None)),
          "worker-3",
          1700000000000L
        )
      }

      "build a markup's text" in {
        lastDecodedEventOf("""run: MarkupProvided(text: "*markup*")""") shouldBe DecodedEvent.MarkupProvided("*markup*", None, "main", 0L)
      }

      "build a starting run's test count" in {
        lastDecodedEventOf("run: RunStarting(testCount: 42)") shouldBe DecodedEvent.RunStarting(42, Map.empty, None, "main", 0L)
      }

      "build an empty list" in {
        lastDecodedEventOf("run: TestFailed(analysis: [])") shouldBe DecodedEvent.TestFailed("", Vector.empty, None, None, None, "main", 0L)
      }

      "build a location that is a line in a file with a pathname" in {
        lastDecodedEventOf("""run: TestStarting(location: LineInFile(7, "FailSuite.scala", Some("/src/FailSuite.scala")))""") shouldBe
          DecodedEvent.TestStarting(Some(LineInFile(7, "FailSuite.scala", Some("/src/FailSuite.scala"))), "main", 0L)
      }

      "build a location that is the top of a class" in {
        lastDecodedEventOf("""run: SuiteStarting(location: TopOfClass("samples.FailSuite"))""") shouldBe
          DecodedEvent.SuiteStarting(Some(TopOfClass("samples.FailSuite")), "main", 0L)
      }

      "build a location that is the top of a method" in {
        lastDecodedEventOf("""run: TestStarting(location: TopOfMethod("samples.FailSuite", "public void samples.FailSuite.fails()"))""") shouldBe
          DecodedEvent.TestStarting(Some(TopOfMethod("samples.FailSuite", "public void samples.FailSuite.fails()")), "main", 0L)
      }

      "build a location that defers to the stack depth exception" in {
        lastDecodedEventOf("run: TestFailed(location: SeeStackDepthException)") shouldBe
          DecodedEvent.TestFailed("", Vector.empty, None, None, Some(SeeStackDepthException), "main", 0L)
      }

      "read a string's escapes, a lone surrogate among them" in {
        lastDecodedEventOf("""run: InfoProvided(message: "line\n\r\ttab \"quoted\" back\\slash \u00e9 \uD800 end")""") shouldBe
          DecodedEvent.InfoProvided("line\n\r\ttab \"quoted\" back\\slash " + 0xE9.toChar + " " + 0xD800.toChar + " end", None, None, "main", 0L)
      }

      "reject a field the event's class does not have" in {
        rejection("""run: RunStopped(message: "stopped")""") shouldBe Some("a RunStopped has no message: 'run: RunStopped(message: \"stopped\")'")
      }

      "reject a field the notation cannot write" in {
        rejection("run: RunCompleted(summary: 3)") shouldBe
          Some("the notation writes no field 'summary' at column 28 of 'run: RunCompleted(summary: 3)'")
      }

      "reject parentheses that hold no field" in {
        rejection("run: RunStopped()") shouldBe Some("expected a field name at column 17 of 'run: RunStopped()'")
      }

      "reject a field written twice" in {
        rejection("""run: InfoProvided(message: "one", message: "two")""") shouldBe
          Some("an event writes its message once: 'run: InfoProvided(message: \"one\", message: \"two\")'")
      }

      "reject an escape Java's string literals do not have" in {
        rejection("""run: InfoProvided(message: "a\qb")""") shouldBe
          Some("the notation has no escape '\\q' at column 31 of 'run: InfoProvided(message: \"a\\qb\")'")
      }

      "reject a Unicode escape of fewer than 4 hexadecimal digits" in {
        rejection("""run: InfoProvided(message: "\u12G4")""") shouldBe
          Some("expected 4 hexadecimal digits at column 31 of 'run: InfoProvided(message: \"\\u12G4\")'")
      }

      "reject a string that ends in a lone backslash" in {
        rejection("run: InfoProvided(message: \"a\\") shouldBe
          Some("an escape that is not finished at column 31 of 'run: InfoProvided(message: \"a\\'")
      }

      "reject a string that is not closed" in {
        rejection("""run: InfoProvided(message: "open)""") shouldBe
          Some("a string that is not closed at column 34 of 'run: InfoProvided(message: \"open)'")
      }

      "reject a number where the field holds a string" in {
        rejection("run: InfoProvided(message: 3)") shouldBe Some("expected '\"' at column 28 of 'run: InfoProvided(message: 3)'")
      }

      "reject a number that does not fit its field" in {
        rejection("run: TestSucceeded(duration: 9223372036854775808)") shouldBe
          Some("'9223372036854775808' does not fit a Long at column 30 of 'run: TestSucceeded(duration: 9223372036854775808)'")
      }

      "reject a test count that does not fit an Int" in {
        rejection("run: RunStarting(testCount: 2147483648)") shouldBe
          Some("the test count 2147483648 does not fit an Int: 'run: RunStarting(testCount: 2147483648)'")
      }

      "reject a location's line number that does not fit an Int" in {
        rejection("""run: TestStarting(location: LineInFile(2147483648, "FailSuite.scala", None))""") shouldBe
          Some("'2147483648' does not fit an Int at column 40 of 'run: TestStarting(location: LineInFile(2147483648, \"FailSuite.scala\", None))'")
      }

      "reject a location that is not one of the Location classes" in {
        rejection("run: TestStarting(location: Somewhere)") shouldBe Some(
          "expected SeeStackDepthException, TopOfClass, TopOfMethod or LineInFile at column 29 of 'run: TestStarting(location: Somewhere)'"
        )
      }
    }

    "defaulting an event's message" should {
      "take the message the event writes, whatever its throwable's" in {
        lastDecodedEventOf("""run: RunAborted(message: "ran out", throwable: java.lang.OutOfMemoryError "heap")""") match {
          case DecodedEvent.RunAborted(message, throwable, None, None, None, "main", 0L) =>
            message shouldBe "ran out"
            throwable.flatMap(_.message) shouldBe Some("heap")
          case other => fail(s"not the RunAborted given: $other")
        }
      }

      "take its throwable's message" in {
        lastDecodedEventOf("""run: TestCanceled(throwable: java.lang.IllegalStateException "boom")""") match {
          case DecodedEvent.TestCanceled(message, Some(_), None, None, "main", 0L) => message shouldBe "boom"
          case other => fail(s"not the TestCanceled given: $other")
        }
      }

      "leave it empty where its throwable's message is null" in {
        lastDecodedEventOf("run: SuiteAborted(throwable: java.lang.IllegalStateException null)") match {
          case DecodedEvent.SuiteAborted(message, Some(_), None, None, "main", 0L) => message shouldBe ""
          case other => fail(s"not the SuiteAborted given: $other")
        }
      }

      "leave it empty where its throwable writes no message" in {
        lastDecodedEventOf("run: NoteProvided(throwable: java.lang.IllegalStateException)") match {
          case DecodedEvent.NoteProvided(message, Some(_), None, "main", 0L) => message shouldBe ""
          case other => fail(s"not the NoteProvided given: $other")
        }
      }
    }

    "given a throwable" should {
      "build one that writes only its class with a null message and no other part" in {
        val throwable = throwableOf("java.lang.IllegalStateException")

        throwable.className shouldBe "java.lang.IllegalStateException"
        throwable.message shouldBe None
        throwable.stackDepth shouldBe None
        throwable.isPropertyCheckFailure shouldBe false
        throwable.stackTrace shouldBe Vector.empty
        throwable.suppressed shouldBe Vector.empty
        throwable.cause shouldBe None
      }

      "build its message" in {
        throwableOf("""samples.Outer$Inner_Failure "went \"wrong\"" """.trim).message shouldBe Some("went \"wrong\"")
      }

      "build a class name that holds a dollar sign and an underscore" in {
        throwableOf("""samples.Outer$Inner_Failure "went wrong"""").className shouldBe "samples.Outer$Inner_Failure"
      }

      "build a null message it writes" in {
        throwableOf("java.lang.IllegalStateException null at StackSpec.scala:9").message shouldBe None
      }

      "build a StackDepth's file name and line number, with no pathname" in {
        throwableOf("""org.scalatest.exceptions.TestFailedException "1 was not 0" at StackFlatSpec.scala:9""").stackDepth shouldBe
          Some(DecodedStackDepth(Some("StackFlatSpec.scala"), None, Some(9)))
      }

      "build a StackDepth's pathname, whose last segment is the file name" in {
        throwableOf("org.scalatest.exceptions.TestFailedException at /src/samples/StackFlatSpec.scala:9").stackDepth shouldBe
          Some(DecodedStackDepth(Some("StackFlatSpec.scala"), Some("/src/samples/StackFlatSpec.scala"), Some(9)))
      }

      "build a StackDepth's Windows pathname" in {
        throwableOf("""org.scalatest.exceptions.TestFailedException at C:\src\StackFlatSpec.scala:9""").stackDepth shouldBe
          Some(DecodedStackDepth(Some("StackFlatSpec.scala"), Some("""C:\src\StackFlatSpec.scala"""), Some(9)))
      }

      "build a StackDepth whose file name is undefined" in {
        throwableOf("org.scalatest.exceptions.TestFailedException at ?:9").stackDepth shouldBe Some(DecodedStackDepth(None, None, Some(9)))
      }

      "build a StackDepth whose line number is undefined" in {
        throwableOf("org.scalatest.exceptions.TestFailedException at StackFlatSpec.scala:?").stackDepth shouldBe
          Some(DecodedStackDepth(Some("StackFlatSpec.scala"), None, None))
      }

      "build a property check failure" in {
        val throwable = throwableOf(
          """org.scalatest.exceptions.GeneratorDrivenPropertyCheckFailedException "falsified" at StackProps.scala:12 property check"""
        )

        throwable.isPropertyCheckFailure shouldBe true
        throwable.stackDepth shouldBe Some(DecodedStackDepth(Some("StackProps.scala"), None, Some(12)))
      }

      "build each frame's fields from the text StackTraceElement.toString gives it" in {
        throwableOf(
          """java.lang.IllegalStateException frames ["samples.ThrowSuite.f(ThrowSuite.scala:9)", "samples.Native.call(Native Method)", "samples.Unknown.run(Unknown Source)", "samples.NoLine.<init>(NoLine.scala)", "app//samples.mod@1.0/samples.InModule.g(InModule.scala:4)"]"""
        ).stackTrace shouldBe Vector(
          DecodedStackFrame("samples.ThrowSuite", "f", Some("ThrowSuite.scala"), Some(9), false, "samples.ThrowSuite.f(ThrowSuite.scala:9)"),
          DecodedStackFrame("samples.Native", "call", None, None, true, "samples.Native.call(Native Method)"),
          DecodedStackFrame("samples.Unknown", "run", None, None, false, "samples.Unknown.run(Unknown Source)"),
          DecodedStackFrame("samples.NoLine", "<init>", Some("NoLine.scala"), None, false, "samples.NoLine.<init>(NoLine.scala)"),
          DecodedStackFrame(
            "samples.InModule",
            "g",
            Some("InModule.scala"),
            Some(4),
            false,
            "app//samples.mod@1.0/samples.InModule.g(InModule.scala:4)"
          )
        )
      }

      "build a chain of causes" in {
        val outer = throwableOf("""java.lang.IllegalStateException "outer" caused by java.io.IOException "middle" caused by java.lang.Error "inner"""")

        val middle = outer.cause.getOrElse(fail("the outer throwable has no cause"))
        val inner = middle.cause.getOrElse(fail("the middle throwable has no cause"))
        (outer.className, outer.message) shouldBe (("java.lang.IllegalStateException", Some("outer")))
        (middle.className, middle.message) shouldBe (("java.io.IOException", Some("middle")))
        (inner.className, inner.message, inner.cause) shouldBe (("java.lang.Error", Some("inner"), None))
      }

      "build suppressed throwables in order, each with its own parts" in {
        val outer = throwableOf(
          """java.lang.IllegalStateException "outer" frames ["samples.ThrowSuite.f(ThrowSuite.scala:9)"] suppressing [java.lang.RuntimeException "closing" caused by java.lang.Error "deep", java.lang.Exception suppressing [java.lang.Error "nested"]] caused by java.io.IOException "inner""""
        )

        outer.stackTrace.map(_.text) shouldBe Vector("samples.ThrowSuite.f(ThrowSuite.scala:9)")
        outer.suppressed.map(suppressed => (suppressed.className, suppressed.message)) shouldBe Vector(
          ("java.lang.RuntimeException", Some("closing")),
          ("java.lang.Exception", None)
        )
        outer.suppressed(0).cause.map(cause => (cause.className, cause.message)) shouldBe Some(("java.lang.Error", Some("deep")))
        outer.suppressed(0).suppressed shouldBe Vector.empty
        outer.suppressed(1).cause shouldBe None
        outer.suppressed(1).suppressed.map(nested => (nested.className, nested.message)) shouldBe Vector(("java.lang.Error", Some("nested")))
        outer.cause.map(cause => (cause.className, cause.message, cause.suppressed.size)) shouldBe Some(("java.io.IOException", Some("inner"), 0))
      }

      "reject a frame that is not the text StackTraceElement.toString gives" in {
        rejection("""run: RunAborted(throwable: java.lang.Error frames ["not a frame"])""") shouldBe
          Some("'not a frame' is not the text StackTraceElement.toString gives a frame")
      }

      "reject a frame whose line number is not a number" in {
        rejection("""run: RunAborted(throwable: java.lang.Error frames ["samples.A.f(A.scala:nine)"])""") shouldBe
          Some("'samples.A.f(A.scala:nine)' holds a line number that is not a number")
      }

      "reject a throwable that writes no class name" in {
        rejection("run: RunAborted(throwable: )") shouldBe
          Some("expected a throwable's class name at column 28 of 'run: RunAborted(throwable: )'")
      }

      "reject a throwable that writes no failed code after at" in {
        rejection("run: RunAborted(throwable: java.lang.Error at )") shouldBe
          Some("expected <file name>:<line number> at column 47 of 'run: RunAborted(throwable: java.lang.Error at )'")
      }

      "reject failed code with no line number" in {
        rejection("run: RunAborted(throwable: java.lang.Error at StackSpec.scala)") shouldBe
          Some("'StackSpec.scala' is not <file name>:<line number> at column 62 of 'run: RunAborted(throwable: java.lang.Error at StackSpec.scala)'")
      }

      "reject failed code whose line number is not a number" in {
        rejection("run: RunAborted(throwable: java.lang.Error at StackSpec.scala:nine)") shouldBe
          Some("'nine' is not a line number at column 67 of 'run: RunAborted(throwable: java.lang.Error at StackSpec.scala:nine)'")
      }

      "reject parts written out of order" in {
        rejection("""run: RunAborted(throwable: java.lang.Error property check at StackSpec.scala:9)""") shouldBe
          Some("expected ')' at column 58 of 'run: RunAborted(throwable: java.lang.Error property check at StackSpec.scala:9)'")
      }
    }

    "recording the parts an event writes" should {
      "record the fields and throwable parts a ScalaTest event writes" in {
        val event = built(
          TestRunPart.Events(
            dedentedAndJoined(stackSuite, """test "pops": TestFailed(throwable: java.lang.IllegalStateException null at StackSpec.scala:? property check frames [] suppressing [java.lang.Error] caused by java.io.IOException "inner", analysis: [])""")
          )
        ).events.last

        event.written shouldBe WrittenParts(
          Set("throwable", "analysis"),
          Set.empty,
          Some(
            WrittenThrowable(
              "java.lang.IllegalStateException",
              WrittenMessage.Null,
              Some(WrittenStackDepth(Some("StackSpec.scala"), None)),
              true,
              Some(Vector.empty),
              Some(Vector(WrittenThrowable("java.lang.Error", WrittenMessage.Unwritten, None, false, None, None, None))),
              Some(WrittenThrowable("java.io.IOException", WrittenMessage.Text("inner"), None, false, None, None, None))
            )
          )
        )
      }

      "record each ancestor whose execution the event marks" in {
        built(TestRunPart.Events(dedentedAndJoined(stackSuite, """test "pops", in scope "A Stack" execution 1: TestStarting"""))).events.last.written shouldBe
          WrittenParts(Set.empty, Set(aStackId), None)
      }

      "record no ancestor where the event leaves each to the default" in {
        built(TestRunPart.Events(dedentedAndJoined(stackSuite, """test "pops" execution 2: TestStarting"""))).events.last.written shouldBe WrittenParts.none
      }

      "keep each event's own notation" in {
        built(TestRunPart.Events(dedentedAndJoined(stackSuite, """test "pops": TestStarting"""))).events.map(_.notation) shouldBe Vector(
          "structure\n  suite \"StackSuite\", class \"samples.StackSuite\"\n    scope \"A Stack\", label \"A Stack\"\n      test \"pops\", label \"- pops\"",
          "test \"pops\": TestStarting"
        )
      }
    }
  }
}
