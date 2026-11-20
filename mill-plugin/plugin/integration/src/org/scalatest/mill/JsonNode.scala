package org.scalatest.mill

import org.scalatest.consolidation.CapturedLine

enum NodeKind(val json: String) {
  case Root extends NodeKind("root")
  case Module extends NodeKind("module")
  case Run extends NodeKind("run")
  case Suite extends NodeKind("suite")
  case Scope extends NodeKind("scope")
  case Test extends NodeKind("test")
}

object NodeKind {

  def fromJson(json: String): NodeKind =
    values.find(_.json == json).getOrElse(throw new IllegalArgumentException(s"No node kind $json"))
}

enum OutputItemKind(val json: String) {
  case Stdio extends OutputItemKind("stdio")
  case Info extends OutputItemKind("info")
  case Note extends OutputItemKind("note")
  case Alert extends OutputItemKind("alert")
  case Markup extends OutputItemKind("markup")
}

object OutputItemKind {

  def fromJson(json: String): OutputItemKind =
    values.find(_.json == json).getOrElse(throw new IllegalArgumentException(s"No output item kind $json"))
}

final class OutputItem(val raw: ujson.Value) {

  def kind: OutputItemKind = OutputItemKind.fromJson(raw("kind").str)

  def message: String = raw("message").str

  def apply(key: String): ujson.Value = raw(key)

  def capturedLine: CapturedLine =
    raw("stream").str match {
      case "stdout" => CapturedLine.Stdout(raw("text").str)
      case "stderr" => CapturedLine.Stderr(raw("text").str)
      case stream => throw new IllegalArgumentException(s"The output item $raw names an unknown stream $stream")
    }

  override def toString: String = raw.toString
}

final class JsonNode(val raw: ujson.Value) {

  def kind: NodeKind = NodeKind.fromJson(raw("kind").str)

  def name: String = raw("name").str

  def label: String = raw("label").str

  def outcome: Option[String] = field("outcome").map(_.str)

  def startedAt: Long = raw("startedAt").num.toLong

  def endedAt: Long = raw("endedAt").num.toLong

  def duration: Long = raw("duration").num.toLong

  def number: Int = raw("number").num.toInt

  def forkGroup: Option[Int] = field("forkGroup").map(_.num.toInt)

  def failureMessages: Seq[String] = raw("failures").arr.toSeq.map(_("message").str)

  def apply(key: String): ujson.Value = raw(key)

  def field(key: String): Option[ujson.Value] = raw.obj.get(key)

  def holds(key: String): Boolean = raw.obj.contains(key)

  def contents: Seq[JsonNode] = raw("contents").arr.toSeq.map(new JsonNode(_))

  def contentNames: Seq[String] = contents.map(_.name)

  def kindsAndNamesOfContents: Seq[(NodeKind, String)] = contents.map(node => (node.kind, node.name))

  /** Every node, this one first, then each node's contents in order, depth first. */
  def nodes: Seq[JsonNode] = this +: field("contents").toSeq.flatMap(_.arr).flatMap(new JsonNode(_).nodes)

  def node(kind: NodeKind): JsonNode =
    nodes.filter(_.kind == kind) match {
      case Seq(found) => found
      case found => throw new NoSuchElementException(s"The JSON output holds ${found.size} nodes of kind ${kind.json}: $raw")
    }

  def node(kind: NodeKind, name: String): JsonNode =
    nodes.filter(found => found.kind == kind && found.field("name").contains(ujson.Str(name))) match {
      case Seq(found) => found
      case found => throw new NoSuchElementException(s"The JSON output holds ${found.size} nodes of kind ${kind.json} named $name: $raw")
    }

  def testOutcome(name: String): Option[String] = nodes.find(found => found.kind == NodeKind.Test && found.name == name).flatMap(_.outcome)

  def output: Seq[OutputItem] = field("output").toSeq.flatMap(_.arr).map(new OutputItem(_))

  def capturedLines: Seq[CapturedLine] = output.filter(_.kind == OutputItemKind.Stdio).map(_.capturedLine)

  def stdoutLines: Seq[String] = capturedLines.collect { case CapturedLine.Stdout(text) => text }.mkString.linesIterator.toSeq

  def stderrLines: Seq[String] = capturedLines.collect { case CapturedLine.Stderr(text) => text }.mkString.linesIterator.toSeq

  override def toString: String = raw.toString
}

object JsonNode {

  def read(path: os.Path): JsonNode = new JsonNode(ujson.read(os.read(path)))
}
