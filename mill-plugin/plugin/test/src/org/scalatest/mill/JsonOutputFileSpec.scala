package org.scalatest.mill

import java.io.IOException
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

      "fail on a folder at its path, and keep the folder with what it holds" in {
        withFolder { folder =>
          val path = Files.createDirectory(folder.resolve("report.json"))
          val kept = Files.writeString(path.resolve("keep.txt"), "kept")
          new JsonOutputFile(path).delete() shouldBe Left(TestCommandFailure.JsonOutputPathIsFolder(path))
          read(kept) shouldBe "kept"
        }
      }

      "fail on an empty folder at its path, and keep the folder" in {
        withFolder { folder =>
          val path = Files.createDirectory(folder.resolve("report.json"))
          new JsonOutputFile(path).delete() shouldBe Left(TestCommandFailure.JsonOutputPathIsFolder(path))
          Files.isDirectory(path) shouldBe true
        }
      }
    }

    "written" should {
      "hold the JSON output in UTF-8, replacing what it held" in {
        withFolder { folder =>
          val path = Files.writeString(folder.resolve("report.json"), "left over, and longer than the output")
          new JsonOutputFile(path).write(_.write("""{"name":"café"}""")) shouldBe Right(())
          read(path) shouldBe """{"name":"café"}"""
        }
      }

      "create its missing parent folders" in {
        withFolder { folder =>
          val path = folder.resolve("missing").resolve("nested").resolve("report.json")
          new JsonOutputFile(path).write(_.write("{}")) shouldBe Right(())
          read(path) shouldBe "{}"
        }
      }

      "fail with the cause, where a parent is a regular file" in {
        withFolder { folder =>
          val blocker = Files.writeString(folder.resolve("blocker"), "a file")
          val path = blocker.resolve("report.json")
          new JsonOutputFile(path).write(_.write("{}")) should matchPattern {
            case Left(TestCommandFailure.JsonOutputNotWritten(`path`, _: IOException, None)) =>
          }
        }
      }

      "fail with the cause, and leave nothing at its path, where the JSON output fails part way through" in {
        withFolder { folder =>
          val path = folder.resolve("report.json")
          val cause = new IOException("no space left")
          val written = new JsonOutputFile(path).write { writer =>
            writer.write("""{"kind":"root",""")
            writer.flush()
            throw cause
          }
          written shouldBe Left(TestCommandFailure.JsonOutputNotWritten(path, cause, None))
          Files.exists(path) shouldBe false
        }
      }
    }
  }
}
