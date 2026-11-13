package compatibility.recorders

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

final class LineFile private (pathname: String) {

  private val writer = Files.newBufferedWriter(Paths.get(pathname), StandardCharsets.UTF_8)

  def append(line: String): Unit =
    synchronized {
      writer.write(line)
      writer.write("\n")
      writer.flush()
    }
}

object LineFile {

  def namedBy(systemProperty: String): LineFile =
    Option(System.getProperty(systemProperty)) match {
      case Some(pathname) => new LineFile(pathname)
      case None => throw new IllegalStateException("System property " + systemProperty + " must name the file to record to")
    }
}
