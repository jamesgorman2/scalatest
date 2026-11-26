package org.scalatest.mill

import java.nio.file.Path
import java.nio.file.Paths
import org.scalatest.consolidation.ModuleName
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

final class ModuleFolderSpec extends AnyWordSpec with should.Matchers {

  private val modulesFolder: Path = Paths.get("out", "org.scalatest.mill.TestCommandModule", "modules").toAbsolutePath

  private def moduleFolderOf(module: String): Path = ModuleFolder.inside(modulesFolder, ModuleName(module))

  "A module folder" when {
    "the module is named as Mill writes it in a task name" should {
      "be the folder of that name inside the modules folder" in {
        moduleFolderOf("core.3_3_8.test") shouldBe modulesFolder.resolve("core.3_3_8.test")
      }
    }

    "the module's name would place it outside the modules folder" should {
      "be refused" in {
        an[IllegalArgumentException] should be thrownBy moduleFolderOf("../core.test")
      }
    }

    "the module's name would place it at the modules folder itself" should {
      "be refused" in {
        for (module <- Seq("", ".", "core.test/..")) {
          an[IllegalArgumentException] should be thrownBy moduleFolderOf(module)
        }
      }
    }

    "the module's name is an absolute path" should {
      "be refused" in {
        an[IllegalArgumentException] should be thrownBy moduleFolderOf(modulesFolder.getRoot.resolve("core.test").toString)
      }
    }
  }
}
