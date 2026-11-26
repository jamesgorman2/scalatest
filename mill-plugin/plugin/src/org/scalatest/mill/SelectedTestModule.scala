package org.scalatest.mill

import mill.api.Evaluator
import mill.api.Result
import mill.api.SelectMode
import mill.javalib.TestModule
import mill.scalajslib.ScalaJSModule
import mill.scalanativelib.ScalaNativeModule
import org.scalatest.consolidation.ModuleName
import org.scalatest.consolidation.ModuleSelector

private[mill] final case class SelectedTestModule(module: TestModule) {

  def name: ModuleName = ModuleName(module.moduleSegments.render)
}

/** Each module selector the test command was given, in the order given, with the test modules it resolved to. */
private[mill] final class ModuleSelection(resolvedSelectors: Seq[(ModuleSelectorArgument, Seq[SelectedTestModule])]) {

  /** Each test module any module selector resolved to, once, ordered by name. */
  val modules: Seq[SelectedTestModule] = ModuleSelection.byName(resolvedSelectors.flatMap(_._2))

  def moduleSelectors: Seq[ModuleSelector] =
    resolvedSelectors.map { case (selector, modules) => selector.resolvedTo(ModuleSelection.byName(modules).map(_.name).toVector) }
}

private[mill] object ModuleSelection {

  private def byName(modules: Seq[SelectedTestModule]): Seq[SelectedTestModule] = modules.distinctBy(_.name).sortBy(_.name.value)
}

private[mill] object SelectedTestModule {

  def resolve(evaluator: Evaluator, moduleSelectors: Seq[ModuleSelectorArgument]): Either[TestCommandFailure, ModuleSelection] =
    if (moduleSelectors.isEmpty) {
      Left(TestCommandFailure.NoModuleSelector)
    } else {
      val resolvedSelectors = moduleSelectors.map(selector => resolveSelector(evaluator, selector).map(selector -> _))
      resolvedSelectors
        .collectFirst { case Left(failure) => failure }
        .toLeft(new ModuleSelection(resolvedSelectors.collect { case Right(resolvedSelector) => resolvedSelector }))
    }

  private def resolveSelector(evaluator: Evaluator, selector: ModuleSelectorArgument): Either[TestCommandFailure, Seq[SelectedTestModule]] =
    evaluator.resolveModulesOrTasks(Seq(selector.text), SelectMode.Multi) match {
      case Result.Success(resolved) =>
        val testModules = resolved.collect { case Left(module: TestModule) => module }
        val (jvmModules, otherModules) = testModules.partition(isJvm)
        if (testModules.isEmpty) {
          Left(TestCommandFailure.NoTestModule(selector))
        } else if (otherModules.nonEmpty) {
          Left(TestCommandFailure.NotJvmTestModules(selector, otherModules.map(SelectedTestModule(_).name)))
        } else {
          Right(jvmModules.map(SelectedTestModule(_)))
        }
      case failure: Result.Failure =>
        Left(TestCommandFailure.UnresolvedModuleSelector(selector, failure.error))
    }

  private def isJvm(module: TestModule): Boolean =
    !module.isInstanceOf[ScalaJSModule] && !module.isInstanceOf[ScalaNativeModule]
}
