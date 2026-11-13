package org.scalatest.compatibility

import java.io.ByteArrayInputStream
import java.io.DataInputStream

object ClassFileMethods {

  def of(classFile: Array[Byte]): Seq[String] = {
    val in = new DataInputStream(new ByteArrayInputStream(classFile))
    in.skipBytes(8)
    val strings = constantPoolStrings(in)
    in.skipBytes(6)
    in.skipBytes(2 * in.readUnsignedShort())
    members(in, strings)
    members(in, strings)
  }

  private def constantPoolStrings(in: DataInputStream): Map[Int, String] = {
    val count = in.readUnsignedShort()
    val strings = Map.newBuilder[Int, String]
    var index = 1
    while (index < count) {
      in.readUnsignedByte() match {
        case 1 => strings += index -> in.readUTF()
        case 7 | 8 | 16 | 19 | 20 => in.skipBytes(2)
        case 15 => in.skipBytes(3)
        case 3 | 4 | 9 | 10 | 11 | 12 | 17 | 18 => in.skipBytes(4)
        case 5 | 6 =>
          in.skipBytes(8)
          index += 1
        case tag => throw new IllegalArgumentException(s"Constant pool tag $tag is not one a class file holds")
      }
      index += 1
    }
    strings.result()
  }

  private def members(in: DataInputStream, strings: Map[Int, String]): Seq[String] =
    List.fill(in.readUnsignedShort()) {
      in.skipBytes(2)
      val member = strings(in.readUnsignedShort()) + strings(in.readUnsignedShort())
      for (_ <- 1 to in.readUnsignedShort()) {
        in.skipBytes(2)
        in.skipBytes(in.readInt())
      }
      member
    }
}
