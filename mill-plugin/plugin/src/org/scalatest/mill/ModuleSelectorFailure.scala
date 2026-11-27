package org.scalatest.mill

private[mill] enum ModuleSelectorFailure {
  case NoTestModule(selector: ModuleSelectorArgument)
  case Unresolved(selector: ModuleSelectorArgument, error: String)

  def line: String =
    this match {
      case NoTestModule(selector) =>
        s"Module selector ${selector.text} resolves to no test module"
      case Unresolved(selector, error) =>
        s"Module selector ${selector.text} resolves to no module: $error"
    }
}
