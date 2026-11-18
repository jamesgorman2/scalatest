package org.scalatest.mill

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

final class JsonOutputFileSpec extends AnyWordSpec with should.Matchers {

  private def withFolder[A](test: Path => A): A = {
    val folder = Files.createTempDirectory("json-output-file")
    try {
      test(folder)
    } finally {
      Files.walk(folder).sorted(java.util.Comparator.reverseOrder()).forEach(path => Files.delete(path))
    }
  }

  private def read(path: Path): String = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  "The JSON output file" when {
    "deleted" should {
      "remove a file at its path" in {
        withFolder { folder =>
          val path = Files.writeString(folder.resolve("report.json"), "left over")
          new JsonOutputFile(path).delete() shouldBe Right(())
          Files.exists(path) shouldBe false
        }
      }

      "succeed where nothing is at its path" in {
        withFolder { folder =>
          new JsonOutputFile(folder.resolve("report.json")).delete() shouldBe Right(())
        }
      }

      "succeed, and keep the file, where a parent is a regular file" in {
        withFolder { folder =>
          val blocker = Files.writeString(folder.resolve("blocker"), "a file")
          new JsonOutputFile(blocker.resolve("report.json")).delete() shouldBe Right(())
          read(blocker) shouldBe "a file"
        }
      }

      "fail, naming its path, and keep a folder at its path with what it holds" in {
        withFolder { folder =>
          val path = Files.createDirectory(folder.resolve("report.json"))
          val kept = Files.writeString(path.resolve("keep.txt"), "kept")
          new JsonOutputFile(path).delete().left.map(_.contains(path.toString)) shouldBe Left(true)
          read(kept) shouldBe "kept"
        }
      }

      "fail, naming its path, and keep an empty folder at its path" in {
        withFolder { folder =>
          val path = Files.createDirectory(folder.resolve("report.json"))
          new JsonOutputFile(path).delete().left.map(_.contains(path.toString)) shouldBe Left(true)
          Files.isDirectory(path) shouldBe true
        }
      }
    }

    "written" should {
      "hold the JSON output in UTF-8, replacing what it held" in {
        withFolder { folder =>
          val path = Files.writeString(folder.resolve("report.json"), "left over, and longer than the output")
          new JsonOutputFile(path).write("""{"name":"café"}""") shouldBe Right(())
          read(path) shouldBe """{"name":"café"}"""
        }
      }

      "create its missing parent folders" in {
        withFolder { folder =>
          val path = folder.resolve("missing").resolve("nested").resolve("report.json")
          new JsonOutputFile(path).write("{}") shouldBe Right(())
          read(path) shouldBe "{}"
        }
      }

      "fail, naming its path, where a parent is a regular file" in {
        withFolder { folder =>
          val blocker = Files.writeString(folder.resolve("blocker"), "a file")
          val path = blocker.resolve("report.json")
          new JsonOutputFile(path).write("{}").left.map(_.contains(path.toString)) shouldBe Left(true)
        }
      }
    }
  }
}
