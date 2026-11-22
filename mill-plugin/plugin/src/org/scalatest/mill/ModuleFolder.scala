package org.scalatest.mill

import java.nio.file.Path
import org.scalatest.consolidation.ModuleName

private[mill] object ModuleFolder {

  def inside(modulesFolder: Path, module: ModuleName): Path = {
    val normalisedModulesFolder = modulesFolder.normalize
    val moduleFolder = normalisedModulesFolder.resolve(module.value).normalize
    require(
      moduleFolder.startsWith(normalisedModulesFolder) && moduleFolder != normalisedModulesFolder,
      s"The module folder of ${module.value} must be strictly inside $normalisedModulesFolder, but is $moduleFolder"
    )
    moduleFolder
  }
}
