package compatibility.recorders

import org.scalatest.events.Event

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import scala.jdk.CollectionConverters.*

object EventClassShapes {

  private val eventsPackage = "org.scalatest.events"
  private val lazyValBookkeeping = """OFFSET\$\d+|\d+bitmap\$\d+""".r
  private val lazyValStorage = """.+\$lzy\d+""".r

  def main(arguments: Array[String]): Unit = {
    val shapes = LineFile.namedBy("compatibility.eventClasses")
    classNames.sorted.map(Class.forName(_, false, getClass.getClassLoader)).foreach(loaded => shapes.append(shape(loaded)))
  }

  private def classNames: List[String] = {
    val location = Paths.get(classOf[Event].getProtectionDomain.getCodeSource.getLocation.toURI)
    if (Files.isDirectory(location)) {
      classNamesUnder(location)
    } else {
      val jar = FileSystems.newFileSystem(location, null: ClassLoader)
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
      .filterNot(field => field.isSynthetic || lazyValBookkeeping.matches(field.getName))
      .map(described)
      .sorted
    val constructors = loaded.getDeclaredConstructors.toList
      .map(constructor => Modifier.toString(constructor.getModifiers) + constructor.getGenericParameterTypes.map(_.getTypeName).mkString("(", ", ", ")"))
      .sorted
    kind + " " + loaded.getName + " fields " + fields.mkString("[", "; ", "]") + " constructors " + constructors.mkString("[", "; ", "]")
  }

  // Scala 3.1 and 3.3 give the field that stores a lazy val different modifiers and types.
  private def described(field: Field): String =
    if (lazyValStorage.matches(field.getName)) {
      field.getName
    } else {
      Modifier.toString(field.getModifiers) + " " + field.getType.getName + " " + field.getName
    }
}
