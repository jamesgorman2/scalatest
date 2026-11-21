package org.scalatest.mill

private[mill] opaque type ClassGlob = String

private[mill] object ClassGlob {

  def apply(text: String): ClassGlob = text

  extension (glob: ClassGlob) {
    def text: String = glob
  }
}
