package samples

import java.nio.file.Files
import java.nio.file.Paths
import org.scalatest.BeforeAndAfterAllConfigMap
import org.scalatest.ConfigMap
import org.scalatest.funsuite.AnyFunSuite

final class ProbeSuite extends AnyFunSuite with BeforeAndAfterAllConfigMap {
  private var report: Option[String] = None

  override def beforeAll(configMap: ConfigMap): Unit =
    report = configMap.getOptional[String]("report")

  test("finds no report") {
    assert(report.exists(path => !Files.exists(Paths.get(path))))
  }
}
