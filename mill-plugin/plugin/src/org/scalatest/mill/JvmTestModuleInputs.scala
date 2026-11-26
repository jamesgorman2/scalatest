package org.scalatest.mill

import mill.api.PathRef
import mill.api.Task
import mill.api.TaskCtx
import mill.api.daemon.internal.TestReporter
import mill.javalib.TestModule
import mill.javalib.TestModuleUtil
import mill.javalib.api.internal.InternalJvmWorkerApi

private[mill] final case class JvmTestModuleInputs(
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
      // The test command writes no JUnit XML report, whatever the module's `testReportXml`.
      None,
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

  def task(module: TestModule): Task[JvmTestModuleInputs] = {
    val discoveredClasses = discoveredClassesTask(module)
    Task.Anon {
      JvmTestModuleInputs(
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
