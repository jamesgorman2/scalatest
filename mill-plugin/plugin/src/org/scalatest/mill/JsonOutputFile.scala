package org.scalatest.mill

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import scala.util.Try

/** The file `-fJ` names, which holds either this test command's JSON output or nothing. */
private[mill] final class JsonOutputFile(val path: Path) {

  /** A folder at the path is never deleted, nor anything in it. A path whose parent is not a folder holds nothing to delete. */
  def delete(): Either[String, Unit] =
    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
      Left(s"The test command cannot delete $path, the file -fJ names: it is a folder")
    } else if (hasParentOtherThanFolder) {
      Right(())
    } else {
      Try(Files.deleteIfExists(path)).toEither.left
        .map(throwable => s"The test command cannot delete $path, the file -fJ names: $throwable")
        .map(_ => ())
    }

  private def hasParentOtherThanFolder: Boolean =
    Option(path.getParent).exists(parent => !Files.isDirectory(parent))

  /** Creates the file's missing parent folders. */
  def write(json: String): Either[String, Unit] =
    Try {
      Option(path.getParent).foreach(parent => Files.createDirectories(parent))
      Files.write(path, json.getBytes(StandardCharsets.UTF_8))
    }.toEither.left
      .map(throwable => s"The test command cannot write its JSON output to $path, the file -fJ names: $throwable")
      .map(_ => ())
}
