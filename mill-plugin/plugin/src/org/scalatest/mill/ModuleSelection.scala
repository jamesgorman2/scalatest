package org.scalatest.mill

import mill.api.Evaluator
import mill.api.Result
import mill.api.SelectMode
import mill.javalib.JavaModule
import mill.javalib.TestModule
import org.scalatest.consolidation.ModuleSelector

private[mill] final case class ModuleSelection(resolvedSelectors: Seq[ResolvedModuleSelector]) {

  val modules: Seq[SelectedTestModule] = ModuleSelection.distinctSortedByName(resolvedSelectors.flatMap(_.modules))

  def moduleSelectors: Seq[ModuleSelector] =
    resolvedSelectors.map(resolved => resolved.selector.resolvedTo(ModuleSelection.distinctSortedByName(resolved.modules).map(_.name).toVector))
}

private[mill] object ModuleSelection {

  def from(evaluator: Evaluator, moduleSelectors: Seq[ModuleSelectorArgument]): Either[TestCommandFailure, ModuleSelection] =
    if (moduleSelectors.isEmpty) {
      Left(TestCommandFailure.NoModuleSelector)
    } else {
      val (failures, resolvedSelectors) = moduleSelectors.map(selector => ResolvedModuleSelector.from(evaluator, selector)).partitionMap(identity)
      if (failures.nonEmpty)
        Left(TestCommandFailure.ModuleSelectorsWithNoTestModule(failures))
      else
        Right(ModuleSelection(resolvedSelectors))
    }

  private def distinctSortedByName(modules: Seq[SelectedTestModule]): Seq[SelectedTestModule] = modules.distinctBy(_.name).sortBy(_.name.value)
}

private[mill] final case class ResolvedModuleSelector(selector: ModuleSelectorArgument, modules: Seq[SelectedTestModule])

private[mill] object ResolvedModuleSelector {

  def from(evaluator: Evaluator, selector: ModuleSelectorArgument): Either[ModuleSelectorFailure, ResolvedModuleSelector] =
    evaluator.resolveModulesOrTasks(Seq(selector.text), SelectMode.Multi) match {
      case Result.Success(resolved) =>
        val testModules = resolved.collect { case Left(module: (TestModule & JavaModule)) => SelectedTestModule(module) }
        if (testModules.isEmpty)
          Left(ModuleSelectorFailure.NoTestModule(selector))
        else
          Right(ResolvedModuleSelector(selector, testModules))
      case failure: Result.Failure =>
        Left(ModuleSelectorFailure.Unresolved(selector, failure.error))
    }
}
