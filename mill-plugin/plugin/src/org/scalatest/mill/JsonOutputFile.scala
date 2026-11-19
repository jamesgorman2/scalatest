package org.scalatest.mill

import java.io.Writer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import scala.util.Try
import scala.util.Using

/**
 * The file `-fJ` names, which holds either this test command's JSON output or nothing, unless the delete after a failed
 * write also fails.
 */
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

  /**
   * Streams the JSON output into the file as UTF-8, creating the file's missing parent folders. Where the write fails
   * part way, the partly written file is deleted.
   *
   * @param writeJson writes the JSON output's text to the writer it is given
   */
  def write(writeJson: Writer => Unit): Either[String, Unit] =
    for {
      writer <- Try(opened()).toEither.left.map(cannotWrite)
      _ <- Try(Using.resource(writer)(writeJson)).toEither.left.map(throwable => cannotWrite(throwable) + partlyWrittenFileDeletion())
    } yield ()

  private def opened(): Writer = {
    Option(path.getParent).foreach(parent => Files.createDirectories(parent))
    Files.newBufferedWriter(path, StandardCharsets.UTF_8)
  }

  private def cannotWrite(throwable: Throwable): String =
    s"The test command cannot write its JSON output to $path, the file -fJ names: $throwable"

  /** Deletes the partly written file, returning an empty string, or, where the delete fails, the text to append to the failure message. */
  private def partlyWrittenFileDeletion(): String =
    Try(Files.deleteIfExists(path)).fold(throwable => s"; it cannot delete the partly written file either: $throwable", _ => "")
}
