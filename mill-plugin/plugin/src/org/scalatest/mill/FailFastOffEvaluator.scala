package org.scalatest.mill

import mill.api.Evaluator
import mill.api.EvaluatorProxy

/**
 * Builds an `Evaluator` over a copy of the command's `mill.exec.Execution` with `failFast` off, so that one `execute`
 * keeps going after a task fails, whether or not Mill's `--keep-going` is given.
 *
 * Neither `mill.eval` nor `mill.exec` is on a build's compile class path, and `EvaluatorImpl.execution` is a
 * `private val`, so every step is reflective. A Mill release that renames or reshapes any of them fails here at run
 * time.
 */
private[mill] object FailFastOffEvaluator {

  def from(evaluator: Evaluator): Evaluator = {
    val impl = withoutProxies(evaluator)
    val executionField = impl.getClass.getDeclaredField("execution")
    executionField.setAccessible(true)
    val execution = executionField.get(impl).asInstanceOf[Product]
    val failFastIndex = execution.productElementNames.indexOf("failFast")
    if (failFastIndex < 0) {
      throw new IllegalStateException(s"${execution.getClass.getName} has no failFast field")
    }
    val fields = execution.productIterator.map(_.asInstanceOf[AnyRef]).toArray
    fields(failFastIndex) = java.lang.Boolean.FALSE
    // Each constructor is chosen by its parameter count, because its parameter types are in packages this code cannot
    // name:
    // - `Execution`'s widest constructor is its primary one, the only one that takes every product field, in product
    //   order. The other builds `profileLogger` itself.
    // - `EvaluatorImpl`'s narrowest constructor takes `allowPositionalCommandArgs`, `selectiveExecution` and
    //   `execution`, and builds its own `scriptModuleInit`. Its primary one also takes `scriptModuleInit`.
    // A Mill release breaks this if it gives `Execution` another constructor at least as wide as its primary one, or if
    // `EvaluatorImpl`'s narrowest constructor no longer takes exactly those three values, in that order.
    val executionCopy = execution.getClass.getConstructors.maxBy(_.getParameterCount).newInstance(fields*)
    impl.getClass.getConstructors
      .minBy(_.getParameterCount)
      .newInstance(millPrivateValue(impl, "allowPositionalCommandArgs"), millPrivateValue(impl, "selectiveExecution"), executionCopy)
      .asInstanceOf[Evaluator]
  }

  private def withoutProxies(evaluator: Evaluator): Evaluator =
    evaluator match {
      case proxy: EvaluatorProxy => withoutProxies(proxy.delegate0())
      case impl => impl
    }

  // `private[mill]` members compile to public methods, which code outside package `mill` reaches only by reflection.
  private def millPrivateValue(evaluator: Evaluator, member: String): AnyRef = evaluator.getClass.getMethod(member).invoke(evaluator)
}
