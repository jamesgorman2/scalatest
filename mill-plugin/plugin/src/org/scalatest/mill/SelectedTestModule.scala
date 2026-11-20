package org.scalatest.mill

import mill.api.Evaluator
import mill.api.Result
import mill.api.SelectMode
import mill.javalib.TestModule
import mill.scalajslib.ScalaJSModule
import mill.scalanativelib.ScalaNativeModule

private[mill] final case class SelectedTestModule(module: TestModule) {

  def path: String = module.moduleSegments.render
}

private[mill] object SelectedTestModule {

  def resolve(evaluator: Evaluator, moduleSelectors: Seq[String]): Either[TestCommandFailure, SelectedTestModule] =
    moduleSelectors match {
      case Seq(selector) =>
        resolveSelector(evaluator, selector)
      case Seq() =>
        Left(TestCommandFailure.NoModuleSelector)
      case _ =>
        Left(TestCommandFailure.SeveralModuleSelectors(moduleSelectors))
    }

  private def resolveSelector(evaluator: Evaluator, selector: String): Either[TestCommandFailure, SelectedTestModule] =
    evaluator.resolveModulesOrTasks(Seq(selector), SelectMode.Multi) match {
      case Result.Success(List(Left(module: TestModule))) if isJvm(module) =>
        Right(SelectedTestModule(module))
      case Result.Success(resolved) =>
        Left(TestCommandFailure.NotOneJvmTestModule(selector, describe(resolved)))
      case failure: Result.Failure =>
        Left(TestCommandFailure.UnresolvedModuleSelector(selector, failure.error))
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
