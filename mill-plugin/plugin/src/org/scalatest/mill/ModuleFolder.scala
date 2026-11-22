package org.scalatest.mill

import java.nio.file.Path
import org.scalatest.consolidation.ModuleName

private[mill] object ModuleFolder {

  /** The module folder of `module` in `modulesFolder`. A module name that would place it anywhere but strictly inside `modulesFolder` is a defect. */
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
