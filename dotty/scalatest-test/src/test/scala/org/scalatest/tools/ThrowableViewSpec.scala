package org.scalatest.tools

import org.scalactic.source.Position
import org.scalatest.Reporter
import org.scalatest.Resources
import org.scalatest.events.DecodedStackDepth
import org.scalatest.events.DecodedStackFrame
import org.scalatest.events.DecodedThrowable
import org.scalatest.events.IndentedText
import org.scalatest.exceptions.GeneratorDrivenPropertyCheckFailedException
import org.scalatest.exceptions.StackDepthException
import org.scalatest.exceptions.TestFailedException
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class ThrowableViewSpec extends AnyWordSpec with Matchers {

  private def frames(count: Int): Array[StackTraceElement] =
    Array.tabulate(count)(index => new StackTraceElement("Stack", "method" + index, "Stack.scala", 10 + index))

  private def withFrames[T <: Throwable](throwable: T, count: Int): T = {
    throwable.setStackTrace(frames(count))
    throwable
  }

  private def testFailedException(message: Option[String], cause: Option[Throwable], position: Position): TestFailedException =
    new TestFailedException((_: StackDepthException) => message, cause, Left(position), None, Vector.empty)

  private def decodedFrames(count: Int): Vector[DecodedStackFrame] =
    frames(count).toVector.map { frame =>
      DecodedStackFrame(frame.getClassName, frame.getMethodName, Some(frame.getFileName), Some(frame.getLineNumber), false, frame.toString)
    }

  private def decoded(
    className: String,
    message: Option[String],
    frameCount: Int,
    stackDepth: Option[DecodedStackDepth] = None,
    isPropertyCheckFailure: Boolean = false,
    cause: Option[DecodedThrowable] = None
  ): DecodedThrowable = {
    val throwable = new DecodedThrowable(className, message, stackDepth, isPropertyCheckFailure, decodedFrames(frameCount))
    throwable.link(cause, Vector.empty)
    throwable
  }

  private def errorLines(
    message: String,
    throwable: Option[ThrowableView],
    analysis: Vector[String] = Vector.empty,
    presentFilePathname: Boolean = false
  ): Vector[String] =
    StringReporter.stringsToPrintOnError(
      Resources.failedNote,
      Resources.testFailed _,
      message,
      throwable,
      analysis,
      Some(IndentedText("    - should pop", "should pop", 2)),
      Some("StackSpec"),
      Some("A stack should pop"),
      None,
      presentUnformatted = false,
      presentAllDurations = false,
      presentShortStackTraces = false,
      presentFullStackTraces = false,
      presentFilePathname = presentFilePathname
    )

  "The view of a live throwable" should {
    "hold the throwable's class name, message, frames and cause" in {
      val view = ThrowableView.of(withFrames(new IllegalStateException("boom", withFrames(new IllegalArgumentException, 1)), 2))
      view.className shouldBe "java.lang.IllegalStateException"
      view.message shouldBe Some("boom")
      view.frames shouldBe Vector("Stack.method0(Stack.scala:10)", "Stack.method1(Stack.scala:11)")
      view.stackDepth shouldBe None
      view.isPropertyCheckFailure shouldBe false
      val cause = view.cause.get
      cause.className shouldBe "java.lang.IllegalArgumentException"
      cause.message shouldBe None
      cause.frames shouldBe Vector("Stack.method0(Stack.scala:10)")
      cause.cause shouldBe None
    }
    "hold a StackDepth exception's file, pathname and line number, and the failed code's stack depth" in {
      val failure = withFrames(testFailedException(Some("failed"), None, Position("Stack.scala", "/src/Stack.scala", 12)), 4)
      val stackDepth = ThrowableView.of(failure).stackDepth.get
      stackDepth.fileNameAndLineNumber shouldBe Some("Stack.scala:12")
      stackDepth.filePathnameAndLineNumber shouldBe Some("/src/Stack.scala:12")
      stackDepth.failedCodeStackDepth shouldBe Some(2)
    }
    "mark a PropertyCheckFailedException as a property check failure" in {
      val failure = new GeneratorDrivenPropertyCheckFailedException(
        (_: StackDepthException) => "falsified",
        None,
        Left(Position("Stack.scala", "/src/Stack.scala", 12)),
        None,
        "undecorated",
        List(1),
        None,
        List.empty
      )
      ThrowableView.of(failure).isPropertyCheckFailure shouldBe true
    }
    "equal another view of the same throwable" in {
      val throwable = new IllegalStateException("boom")
      ThrowableView.of(throwable) shouldBe ThrowableView.of(throwable)
      ThrowableView.of(throwable).hashCode shouldBe ThrowableView.of(throwable).hashCode
    }
    "differ from the view of another throwable with the same content" in {
      ThrowableView.of(new IllegalStateException("boom")) should not be ThrowableView.of(new IllegalStateException("boom"))
    }
  }

  "A decoded stack depth" should {
    "join its file name and its pathname to its line number" in {
      val stackDepth = DecodedStackDepth(Some("Stack.scala"), Some("/src/Stack.scala"), Some(12))
      stackDepth.fileNameAndLineNumber shouldBe Some("Stack.scala:12")
      stackDepth.filePathnameAndLineNumber shouldBe Some("/src/Stack.scala:12")
    }
    "hold no file name and line number without a line number" in {
      val stackDepth = DecodedStackDepth(Some("Stack.scala"), Some("/src/Stack.scala"), None)
      stackDepth.fileNameAndLineNumber shouldBe None
      stackDepth.filePathnameAndLineNumber shouldBe None
    }
    "hold no pathname and line number without a pathname" in {
      val stackDepth = DecodedStackDepth(Some("Stack.scala"), None, Some(12))
      stackDepth.fileNameAndLineNumber shouldBe Some("Stack.scala:12")
      stackDepth.filePathnameAndLineNumber shouldBe None
    }
    "hold no stack depth for the failed code" in {
      DecodedStackDepth(Some("Stack.scala"), None, Some(12)).failedCodeStackDepth shouldBe None
    }
  }

  "A decoded throwable" should {
    "give each frame's text as its frames" in {
      decoded("java.lang.IllegalStateException", Some("boom"), 2).frames shouldBe
        Vector("Stack.method0(Stack.scala:10)", "Stack.method1(Stack.scala:11)")
    }
  }

  "StringReporter's error lines" should {
    "leave every line unindented after the first, which is the formatter's text" in {
      val cause = withFrames(new IllegalArgumentException("root cause"), 1)
      val failure = withFrames(new IllegalStateException("boom", cause), 2)
      errorLines("the message", Some(ThrowableView.of(failure)), Vector("an analysis line")) shouldBe Vector(
        "    - should pop *** FAILED ***",
        "Analysis:",
        "an analysis line",
        "java.lang.IllegalStateException: boom",
        "at Stack.method0(Stack.scala:10)",
        "at Stack.method1(Stack.scala:11)",
        "...",
        "Cause: java.lang.IllegalArgumentException: root cause",
        "at Stack.method0(Stack.scala:10)",
        "..."
      )
    }
    "be the same for a decoded throwable as for the live throwable it was decoded from" in {
      val cause = withFrames(new IllegalArgumentException("root cause"), 1)
      val failure = withFrames(new IllegalStateException("boom", cause), 2)
      val decodedCause = decoded("java.lang.IllegalArgumentException", Some("root cause"), 1)
      val decodedFailure = decoded("java.lang.IllegalStateException", Some("boom"), 2, cause = Some(decodedCause))
      errorLines("the message", Some(decodedFailure)) shouldBe errorLines("the message", Some(ThrowableView.of(failure)))
    }
    "give a decoded StackDepth exception's message with its file name and line number" in {
      val failure = decoded(
        "org.scalatest.exceptions.TestFailedException",
        Some("1 did not equal 2"),
        3,
        Some(DecodedStackDepth(Some("Stack.scala"), Some("/src/Stack.scala"), Some(12)))
      )
      errorLines("1 did not equal 2", Some(failure)) shouldBe Vector(
        "    - should pop *** FAILED ***",
        "1 did not equal 2 (Stack.scala:12)"
      )
    }
    "give a decoded StackDepth exception's pathname on a line of its own where pathnames are presented" in {
      val failure = decoded(
        "org.scalatest.exceptions.TestFailedException",
        Some("1 did not equal 2"),
        3,
        Some(DecodedStackDepth(Some("Stack.scala"), Some("/src/Stack.scala"), Some(12)))
      )
      errorLines("1 did not equal 2", Some(failure), presentFilePathname = true) shouldBe Vector(
        "    - should pop *** FAILED ***",
        "1 did not equal 2",
        "** /src/Stack.scala:12 **"
      )
    }
    "give a decoded property check failure's message without a file name and line number" in {
      val failure = decoded(
        "org.scalatest.exceptions.GeneratorDrivenPropertyCheckFailedException",
        Some("falsified"),
        3,
        Some(DecodedStackDepth(Some("Stack.scala"), Some("/src/Stack.scala"), Some(12))),
        isPropertyCheckFailure = true
      )
      errorLines("falsified", Some(failure)) shouldBe Vector("    - should pop *** FAILED ***", "falsified")
    }
  }

  "StringReporter's stack trace lines" should {
    "show the top ten frames of a decoded StackDepth exception under short stack traces" in {
      val failure = decoded(
        "org.scalatest.exceptions.TestFailedException",
        Some("1 did not equal 2"),
        12,
        Some(DecodedStackDepth(Some("Stack.scala"), None, Some(12)))
      )
      StringReporter.stackTraceLines(Some(failure), presentShortStackTraces = true, presentFullStackTraces = false) shouldBe
        ("org.scalatest.exceptions.TestFailedException:" +: decodedFrames(10).map("at " + _.text) :+ "...")
    }
    "stop at a decoded throwable that is its own cause" in {
      val failure = new DecodedThrowable("java.lang.IllegalStateException", Some("boom"), None, false, decodedFrames(1))
      failure.link(Some(failure), Vector.empty)
      StringReporter.stackTraceLines(Some(failure), presentShortStackTraces = false, presentFullStackTraces = true) shouldBe
        List("java.lang.IllegalStateException: boom", "at Stack.method0(Stack.scala:10)")
    }
    "stop at a live throwable that its cause chain has already shown" in {
      val outer = withFrames(new IllegalStateException("outer"), 1)
      val inner = withFrames(new IllegalArgumentException("inner", outer), 1)
      outer.initCause(inner)
      StringReporter.stackTraceLines(Some(ThrowableView.of(outer)), presentShortStackTraces = false, presentFullStackTraces = true) shouldBe
        List(
          "java.lang.IllegalStateException: outer",
          "at Stack.method0(Stack.scala:10)",
          "Cause: java.lang.IllegalArgumentException: inner",
          "at Stack.method0(Stack.scala:10)"
        )
    }
    "show every throwable of a cause chain too long to recurse over" in {
      val chainLength = 100000
      val innermost = decoded("java.lang.IllegalArgumentException", None, 0)
      val outermost = (1 until chainLength).foldLeft(innermost) { (cause, _) =>
        decoded("java.lang.IllegalArgumentException", None, 0, cause = Some(cause))
      }
      val lines = StringReporter.stackTraceLines(Some(outermost), presentShortStackTraces = false, presentFullStackTraces = true)
      lines.head shouldBe "java.lang.IllegalArgumentException:"
      lines.tail.distinct shouldBe List("Cause: java.lang.IllegalArgumentException:")
      lines.size shouldBe chainLength
    }
  }

  "Reporter.messageOrThrowablesDetailMessage" should {
    "give the trimmed message where it is not blank" in {
      val throwable = decoded("java.lang.IllegalStateException", Some("boom"), 0)
      Reporter.messageOrThrowablesDetailMessage("  the message  ", Some(throwable)) shouldBe "the message"
    }
    "give the throwable's trimmed message where the message is blank" in {
      val throwable = decoded("java.lang.IllegalStateException", Some("  boom  "), 0)
      Reporter.messageOrThrowablesDetailMessage("  ", Some(throwable)) shouldBe "boom"
    }
    "give an empty message where the message is blank and there is no throwable" in {
      Reporter.messageOrThrowablesDetailMessage("  ", None) shouldBe ""
    }
  }
}
