package mill.scalatestplugin

import mill.api.Logger

/** Mill's colour decision, which Mill gives a task only through `private[mill]` API. */
object MillColour {

  /** Whether Mill colours its own console output, weighing `--color`, the console, `NO_COLOR` and BSP mode. */
  def colours(log: Logger): Boolean = log.prompt.colored
}
