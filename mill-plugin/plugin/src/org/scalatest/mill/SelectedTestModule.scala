package org.scalatest.mill

import mill.api.Evaluator
import mill.api.Result
import mill.api.SelectMode
import mill.javalib.TestModule
import mill.scalajslib.ScalaJSModule
import mill.scalanativelib.ScalaNativeModule

/** The one JVM test module the test command's module selector resolves to. */
private[mill] final case class SelectedTestModule(module: TestModule) {

  /** The module's path, such as `core.test`. */
  def path: String = module.moduleSegments.render
}

private[mill] object SelectedTestModule {

  def resolve(evaluator: Evaluator, moduleSelectors: Seq[String]): Either[String, SelectedTestModule] =
    moduleSelectors match {
      case Seq(selector) =>
        resolveSelector(evaluator, selector)
      case Seq() =>
        Left("The test command takes one module selector, but was given none")
      case _ =>
        Left(s"The test command takes one module selector, but was given: ${moduleSelectors.mkString(" ")}")
    }

  private def resolveSelector(evaluator: Evaluator, selector: String): Either[String, SelectedTestModule] =
    evaluator.resolveModulesOrTasks(Seq(selector), SelectMode.Multi) match {
      case Result.Success(List(Left(module: TestModule))) if isJvm(module) =>
        Right(SelectedTestModule(module))
      case Result.Success(resolved) =>
        Left(s"Module selector $selector must resolve to one JVM test module, but resolved to: ${describe(resolved)}")
      case failure: Result.Failure =>
        Left(s"Module selector $selector resolves to no module: ${failure.error}")
    }

  private def isJvm(module: TestModule): Boolean =
    !module.isInstanceOf[ScalaJSModule] && !module.isInstanceOf[ScalaNativeModule]

  private def describe(resolved: List[Either[mill.api.Module, mill.api.Task.Named[?]]]): String =
    if (resolved.isEmpty) {
      "nothing"
    } else {
      resolved.map(_.fold(_.moduleSegments.render, _.toString)).mkString(", ")
    }
}
