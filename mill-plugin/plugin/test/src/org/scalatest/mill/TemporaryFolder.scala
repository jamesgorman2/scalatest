package org.scalatest.mill

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import scala.util.Using

private[mill] object TemporaryFolder {

  def within[A](prefix: String)(test: Path => A): A = {
    val folder = Files.createTempDirectory(prefix)
    try {
      test(folder)
    } finally {
      Using.resource(Files.walk(folder))(paths => paths.sorted(Comparator.reverseOrder()).forEach(path => Files.delete(path)))
    }
  }
}
