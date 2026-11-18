package org.scalatest.compatibility

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import scala.jdk.CollectionConverters.*

object InlineAccessors {

  private val libraryPackages = List("org/scalatest", "org/scalactic")

  def onClasspath(classpath: String): Set[String] =
    classpath.split(File.pathSeparator).toList.map(Paths.get(_)).flatMap(inEntry).toSet

  private def inEntry(entry: Path): List[String] =
    if (Files.isDirectory(entry)) {
      under(entry)
    } else if (entry.toString.endsWith(".jar") && Files.exists(entry)) {
      val jar = FileSystems.newFileSystem(entry, null: ClassLoader)
      try under(jar.getPath("/")) finally jar.close()
    } else {
      Nil
    }

  private def under(root: Path): List[String] =
    libraryPackages.map(root.resolve).filter(Files.isDirectory(_)).flatMap { libraryPackage =>
      val files = Files.walk(libraryPackage)
      try {
        files.iterator.asScala
          .filter(_.toString.endsWith(".class"))
          .flatMap(file => ofClass(root.relativize(file).iterator.asScala.mkString("/").stripSuffix(".class"), Files.readAllBytes(file)))
          .toList
      } finally {
        files.close()
      }
    }

  private def ofClass(className: String, classFile: Array[Byte]): Seq[String] =
    ClassFileMethods.of(classFile).filter(_.contains("inline$")).map(className + "." + _)
}
