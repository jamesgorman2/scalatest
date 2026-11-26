package org.scalatest.consolidation

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties
import scala.jdk.CollectionConverters.*
import com.networknt.schema.InputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion

private[consolidation] object MillPluginDocuments {

  private val millPluginDirectory: Path = {
    val properties = new Properties
    val resource = Option(getClass.getResourceAsStream("/repository.properties"))
      .getOrElse(throw new IllegalStateException("the test resource repository.properties is not on the classpath"))
    try properties.load(resource) finally resource.close()
    Option(properties.getProperty("millPlugin"))
      .map(Paths.get(_))
      .getOrElse(throw new IllegalStateException("repository.properties does not give the millPlugin directory"))
  }

  private lazy val consolidatedSchema: Schema =
    SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(read("consolidated.schema.json"), InputFormat.JSON)

  def read(name: String): String = Files.readString(millPluginDirectory.resolve(name), StandardCharsets.UTF_8)

  def schemaViolationsOf(json: String): Vector[String] = consolidatedSchema.validate(json, InputFormat.JSON).asScala.toVector.map(_.toString)
}
