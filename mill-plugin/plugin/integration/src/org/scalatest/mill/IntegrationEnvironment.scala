package org.scalatest.mill

object IntegrationEnvironment {

  val pluginVersion: String = sys.env("SCALATEST_MILL_VERSION")

  private val fixtures: os.Path = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))

  val millExecutable: os.Path = os.Path(sys.env("MILL_EXECUTABLE_PATH"))

  val millBootstrapScript: os.Path = os.Path(sys.env("MILL_BOOTSTRAP_SCRIPT_PATH"))

  val pluginHeaderLine: String = s"""//| mvnDeps: ["com.edc::scalatest-mill::$pluginVersion"]"""

  def fixture(name: FixtureName): os.Path = fixtures / name.value
}
