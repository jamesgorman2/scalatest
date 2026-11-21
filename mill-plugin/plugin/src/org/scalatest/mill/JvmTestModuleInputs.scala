package org.scalatest.mill

import mill.api.Evaluator
import mill.api.Logger
import mill.api.PathRef
import mill.api.Result
import mill.api.Task
import mill.api.TaskCtx
import mill.api.daemon.internal.TestReporter
import mill.javalib.TestModule
import mill.javalib.TestModuleUtil
import mill.javalib.api.internal.InternalJvmWorkerApi

private[mill] final case class JvmTestModuleInputs(
    testArgs: Seq[String],
    useArgsFile: Boolean,
    forkArgs: Seq[String],
    scalalibClasspath: Seq[PathRef],
    resources: Seq[PathRef],
    testFramework: String,
    runClasspath: Seq[PathRef],
    testClasspath: Seq[PathRef],
    forkGroups: Seq[Seq[String]],
    testrunnerEntrypointClasspath: Seq[PathRef],
    forkEnv: Map[String, String],
    testSandboxWorkingDir: Boolean,
    forkWorkingDir: os.Path,
    testReportXml: Option[String],
    javaHome: Option[os.Path],
    testParallelism: Boolean,
    testLogLevel: TestReporter.LogLevel,
    propagateEnv: Boolean,
    jvmWorker: InternalJvmWorkerApi,
    discoveredClasses: Option[Seq[(String, Int)]],
    testBatchFrameworkTasks: Boolean
) {

  def testModuleUtil(arguments: Seq[String], ctx: TaskCtx): TestModuleUtil =
    new TestModuleUtil(
      useArgsFile,
      forkArgs,
      Nil,
      scalalibClasspath,
      resources,
      testFramework,
      runClasspath,
      testClasspath,
      arguments,
      forkGroups,
      testrunnerEntrypointClasspath,
      forkEnv,
      testSandboxWorkingDir,
      forkWorkingDir,
      testReportXml,
      javaHome,
      testParallelism,
      // At `Info` or above, Mill writes no `Running Test Class` line to the workers' stderr.
      if (testLogLevel.level < TestReporter.LogLevel.Info.level) TestReporter.LogLevel.Info else testLogLevel,
      propagateEnv,
      jvmWorker,
      discoveredClasses,
      testBatchFrameworkTasks
    )(using ctx)
}

private[mill] object JvmTestModuleInputs {

  def evaluate(evaluator: Evaluator, module: TestModule, logger: Logger): Either[TestCommandFailure, JvmTestModuleInputs] =
    evaluator.execute(Seq(task(module)), logger = logger).values match {
      case Result.Success(Seq(inputs)) => Right(inputs)
      case Result.Success(values) => throw new IllegalStateException(s"Evaluating ${module.moduleSegments.render} gave ${values.size} values")
      case failure: Result.Failure => Left(TestCommandFailure.ModuleInputsNotEvaluated(failure.error))
    }

  private def task(module: TestModule): Task[JvmTestModuleInputs] = {
    val discoveredClasses = discoveredClassesTask(module)
    Task.Anon {
      JvmTestModuleInputs(
        module.testArgsDefault(),
        module.testUseArgsFile(),
        module.forkArgs(),
        module.jvmWorker().scalalibClasspath(),
        module.resources(),
        module.testFramework(),
        module.runClasspath(),
        module.testClasspath(),
        module.testForkGrouping(),
        module.jvmWorker().testrunnerEntrypointClasspath(),
        module.allForkEnv(),
        module.testSandboxWorkingDir(),
        module.forkWorkingDir(),
        module.testReportXml(),
        module.javaHome().map(_.path),
        module.testParallelism(),
        module.testLogLevel(),
        module.propagateEnv(),
        module.jvmWorker().internalWorker(),
        discoveredClasses(),
        module.testBatchFrameworkTasks()
      )
    }
  }

  private def discoveredClassesTask(module: TestModule): Task[Option[Seq[(String, Int)]]] =
    // `aheadOfTimeDiscoveredTestClassesIfNeeded` is protected, but compiles to a public method.
    classOf[TestModule]
      .getMethod("aheadOfTimeDiscoveredTestClassesIfNeeded")
      .invoke(module)
      .asInstanceOf[Task[Option[Seq[(String, Int)]]]]
}
