package org.scalatest.mill.tags

import org.scalatest.Tag

import java.lang.annotation.Annotation

enum AreaTag(annotation: Class[? <: Annotation]) extends Tag(annotation.getName) {
  case Arguments extends AreaTag(classOf[_root_.org.scalatest.mill.tags.Arguments])
  case Modules extends AreaTag(classOf[_root_.org.scalatest.mill.tags.Modules])
  case Workers extends AreaTag(classOf[_root_.org.scalatest.mill.tags.Workers])
  case Capture extends AreaTag(classOf[_root_.org.scalatest.mill.tags.Capture])
  case HumanReadable extends AreaTag(classOf[_root_.org.scalatest.mill.tags.HumanReadable])
  case Json extends AreaTag(classOf[_root_.org.scalatest.mill.tags.Json])
  case MillCoexistence extends AreaTag(classOf[_root_.org.scalatest.mill.tags.MillCoexistence])
}
