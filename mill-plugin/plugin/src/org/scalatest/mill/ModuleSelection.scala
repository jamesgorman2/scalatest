package org.scalatest.mill

import mill.api.Evaluator
import mill.api.Result
import mill.api.SelectMode
import mill.javalib.JavaModule
import mill.javalib.TestModule
import mill.scalajslib.ScalaJSModule
import mill.scalanativelib.ScalaNativeModule
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
      FirstFailure.orValues(moduleSelectors.map(selector => ResolvedModuleSelector.from(evaluator, selector))).map(ModuleSelection(_))
    }

  private def distinctSortedByName(modules: Seq[SelectedTestModule]): Seq[SelectedTestModule] = modules.distinctBy(_.name).sortBy(_.name.value)
}

private[mill] final case class ResolvedModuleSelector(selector: ModuleSelectorArgument, modules: Seq[SelectedTestModule])

private[mill] object ResolvedModuleSelector {

  def from(evaluator: Evaluator, selector: ModuleSelectorArgument): Either[TestCommandFailure, ResolvedModuleSelector] =
    evaluator.resolveModulesOrTasks(Seq(selector.text), SelectMode.Multi) match {
      case Result.Success(resolved) =>
        val testModules = resolved.collect { case Left(module: (TestModule & JavaModule)) => module }
        val (jvmModules, otherModules) = testModules.partition(isJvm)
        if (testModules.isEmpty) {
          Left(TestCommandFailure.NoTestModule(selector))
        } else if (otherModules.nonEmpty) {
          Left(TestCommandFailure.NotJvmTestModules(selector, otherModules.map(SelectedTestModule(_).name)))
        } else {
          Right(ResolvedModuleSelector(selector, jvmModules.map(SelectedTestModule(_))))
        }
      case failure: Result.Failure =>
        Left(TestCommandFailure.UnresolvedModuleSelector(selector, failure.error))
    }

  private def isJvm(module: TestModule & JavaModule): Boolean =
    !module.isInstanceOf[ScalaJSModule] && !module.isInstanceOf[ScalaNativeModule]
}
