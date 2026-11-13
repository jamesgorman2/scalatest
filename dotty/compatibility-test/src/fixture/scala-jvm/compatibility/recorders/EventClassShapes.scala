package compatibility.recorders

import org.scalatest.events.Event

import java.lang.reflect.Modifier
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Collections
import scala.jdk.CollectionConverters.*

object EventClassShapes {

  private val eventsPackage = "org.scalatest.events"

  def main(arguments: Array[String]): Unit = {
    val shapes = LineFile.namedBy("compatibility.eventClasses")
    classNames.sorted.map(Class.forName(_, false, getClass.getClassLoader)).foreach(loaded => shapes.append(shape(loaded)))
  }

  private def classNames: List[String] = {
    val location = Paths.get(classOf[Event].getProtectionDomain.getCodeSource.getLocation.toURI)
    if (Files.isDirectory(location)) {
      classNamesUnder(location)
    } else {
      val jar = FileSystems.newFileSystem(location, Collections.emptyMap[String, Any]())
      try classNamesUnder(jar.getPath("/")) finally jar.close()
    }
  }

  private def classNamesUnder(root: Path): List[String] = {
    val entries = Files.list(root.resolve(eventsPackage.replace('.', '/')))
    try {
      entries.iterator.asScala
        .map(_.getFileName.toString)
        .collect { case name if name.endsWith(".class") => eventsPackage + "." + name.stripSuffix(".class") }
        .toList
    } finally {
      entries.close()
    }
  }

  private def shape(loaded: Class[?]): String = {
    val kind = if (classOf[Event].isAssignableFrom(loaded)) "event class" else "class"
    val fields = loaded.getDeclaredFields.toList
      .filterNot(field => field.isSynthetic || field.getName.contains("$"))
      .map(field => Modifier.toString(field.getModifiers) + " " + field.getType.getName + " " + field.getName)
      .sorted
    val constructors = loaded.getDeclaredConstructors.toList
      .map(constructor => Modifier.toString(constructor.getModifiers) + constructor.getGenericParameterTypes.map(_.getTypeName).mkString("(", ", ", ")"))
      .sorted
    kind + " " + loaded.getName + " fields " + fields.mkString("[", "; ", "]") + " constructors " + constructors.mkString("[", "; ", "]")
  }
}
