package org.scalatest.mill

import java.io.Writer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import scala.util.Try
import scala.util.Using

private[mill] final class JsonOutputFile(val path: Path) {

  def delete(): Either[TestCommandFailure, Unit] =
    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
      Left(TestCommandFailure.JsonOutputPathIsFolder(path))
    } else if (hasParentOtherThanFolder) {
      // A path whose parent is not a folder holds nothing to delete.
      Right(())
    } else {
      Try(Files.deleteIfExists(path)).toEither.left
        .map(cause => TestCommandFailure.JsonOutputNotDeleted(path, cause))
        .map(_ => ())
    }

  private def hasParentOtherThanFolder: Boolean =
    Option(path.getParent).exists(parent => !Files.isDirectory(parent))

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

  private def deletePartlyWrittenFile(): Option[Throwable] =
    Try(Files.deleteIfExists(path)).failed.toOption
}
