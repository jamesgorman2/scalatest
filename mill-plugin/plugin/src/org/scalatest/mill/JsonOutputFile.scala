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
  def delete(): Either[TestCommandFailure, Unit] =
    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
      Left(TestCommandFailure.JsonOutputPathIsFolder(path))
    } else if (hasParentOtherThanFolder) {
      Right(())
    } else {
      Try(Files.deleteIfExists(path)).toEither.left
        .map(cause => TestCommandFailure.JsonOutputNotDeleted(path, cause))
        .map(_ => ())
    }

  private def hasParentOtherThanFolder: Boolean =
    Option(path.getParent).exists(parent => !Files.isDirectory(parent))

  /**
   * Streams the JSON output into the file as UTF-8, creating the file's missing parent folders. Where the write fails
   * part way, the partly written file is deleted.
   *
   * @param writeJson passes the JSON output's text, piece by piece, to the function it receives
   */
  def write(writeJson: (String => Unit) => Unit): Either[TestCommandFailure, Unit] =
    for {
      writer <- Try(opened()).toEither.left.map(cause => TestCommandFailure.JsonOutputNotWritten(path, cause, None))
      _ <- Try(Using.resource(writer)(writer => writeJson(text => writer.write(text)))).toEither.left
        .map(cause => TestCommandFailure.JsonOutputNotWritten(path, cause, deletePartlyWrittenFile()))
    } yield ()

  private def opened(): Writer = {
    Option(path.getParent).foreach(parent => Files.createDirectories(parent))
    Files.newBufferedWriter(path, StandardCharsets.UTF_8)
  }

  /** Deletes the partly written file, giving the delete's failure, if any. */
  private def deletePartlyWrittenFile(): Option[Throwable] =
    Try(Files.deleteIfExists(path)).failed.toOption
}
