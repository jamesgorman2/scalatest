package org.scalatest.mill

import mill.api.PathRef
import mill.testkit.IntegrationTester
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should

final class HeaderPluginSpec extends AnyFreeSpec with should.Matchers with FixtureBuilds {

  private def classpath(result: IntegrationTester.EvalResult): Seq[Seq[String]] = {
    withClue(result.debugString) {
      result.isSuccess shouldBe true
    }
    upickle.default.read[Seq[PathRef]](result.out).map(_.path.segments.toSeq)
  }

  // An Ivy repository names the group in one folder; a Maven repository, one folder per part.
  private def publishedArtifact(group: String, artifact: String): Seq[String] => Boolean = { segments =>
    val artifactFolder = segments.indexOfSlice(Seq(artifact, pluginVersion))
    val groupFolders = segments.take(artifactFolder)
    artifactFolder > 0 && (groupFolders.endsWith(Seq(group)) || groupFolders.endsWith(group.split('.').toSeq))
  }

  "A build whose header adds the plugin by one line" - {
    for (scala <- Seq("3.3", "3.9")) {
      s"with a test module on Scala $scala" - {
        val fixture = s"scala-$scala"

        "has that line, naming the plugin's own version, as its whole header" in {
          val header = os.read.lines(fixtures / fixture / "build.mill").takeWhile(_.startsWith("//|"))
          header shouldBe Seq(pluginHeaderLine)
        }

        "loads with the plugin, scalatest-core and the consolidation library on its meta-build's classpath" in {
          withTester(fixture) { tester =>
            val entries = classpath(tester.eval(Seq("--meta-level", "1", "show", "compileClasspath")))
            entries.exists(publishedArtifact("com.edc", "scalatest-mill_mill1_3")) shouldBe true
            entries.exists(publishedArtifact("com.edc", "scalatest-core_3")) shouldBe true
            entries.exists(publishedArtifact("com.edc", "scalatest-consolidation_3")) shouldBe true
          }
        }

        "resolves core.test's scalatest and scalactic from the fork" in {
          withTester(fixture) { tester =>
            val entries = classpath(tester.eval(Seq("show", "core.test.compileClasspath")))
            val scalatestJars = entries.filter(entry => entry.last.startsWith("scalatest") || entry.last.startsWith("scalactic"))
            entries.exists(publishedArtifact("com.edc", "scalatest_3")) shouldBe true
            all(scalatestJars) should contain("com.edc")
          }
        }

        "passes core.test.testForked" in {
          withTester(fixture) { tester =>
            val result = tester.eval("core.test.testForked")
            withClue(result.debugString) {
              result.isSuccess shouldBe true
              result.out should include("Tests: succeeded 1, failed 0, canceled 0, ignored 0, pending 0")
            }
          }
        }
      }
    }
  }
}
