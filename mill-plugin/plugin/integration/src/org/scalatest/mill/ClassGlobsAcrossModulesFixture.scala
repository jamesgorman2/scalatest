package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.Suite
import org.scalatest.matchers.should

trait ClassGlobsAcrossModulesFixture extends SeveralModulesFixture { this: Suite & should.Matchers & GivenWhenThen =>

  protected def sharedClassGlobsAcrossModules(setUp: FixtureBuild => Unit): SharedFixtureBuild =
    sharedFixtureBuild(FixtureName.classGlobsAcrossModules, WorkspaceReset.emptyMarkersFolder.and(WorkspaceReset.noJsonOutputFile))(setUp)

  protected def inSharedClassGlobsAcrossModules[A](sharedBuild: SharedFixtureBuild)(test: FixtureBuild => A): A = {
    givenTheClassGlobsAcrossModules()
    sharedBuild.use(test)
  }

  private def givenTheClassGlobsAcrossModules(): Unit = {
    Given("the JVM test modules alpha.test, holding Alpha1 and Alpha2, and beta.test, holding Beta1")
    And("each of those suites is an AnyFunSuite with two tests, fast and slow, both of which succeed, and only fast is tagged samples.Fast")
    And("each of those modules holds the object samples.Fast, which is a Tag and not a test class")
    And("<markers> is an absolute path at which an empty folder exists, and each test, when it runs, creates the file <suite>.<test> in <markers>")
    And("the test command is invoked as mill -j 2 scalatest/test alpha.test beta.test -fJ <report> <arguments>, each argument reaching mill exactly as written, with no shell expansion")
  }

  protected def runOnBothModulesParsingJsonOutput(build: FixtureBuild, arguments: String*): (TestCommandResult, JsonNode) = {
    When(s"the test command is invoked with ${arguments.mkString(" ")}")
    build.resultAndJsonOutput(runOnBothModulesWithReport(build, arguments*))
  }

  protected def runOnBothModulesWithReport(build: FixtureBuild, arguments: String*): TestCommandResult =
    runTestCommand(build)((Seq("alpha.test", "beta.test", "-fJ", build.jsonOutputFile.toString) ++ arguments)*)
}
