package org.scalatest.mill

private[mill] opaque type Organization = String

private[mill] object Organization {

  def apply(value: String): Organization = value

  extension (organization: Organization) {
    def value: String = organization
  }
}
