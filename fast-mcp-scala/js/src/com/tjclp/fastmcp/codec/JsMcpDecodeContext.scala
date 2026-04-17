package com.tjclp.fastmcp
package codec

import scala.scalajs.js
import scala.scalajs.js.JSON

import com.tjclp.fastmcp.core.McpDecodeContext

/** Scala.js implementation of [[McpDecodeContext]]. Uses `js.JSON` for stringify/parse and knows
  * how to turn Scala-side `Map` / `List` structures back into JS values when serializing.
  *
  * The JS MCP backend constructs a Scala `Map[String, Any]` shallowly from the TS SDK's incoming
  * argument object; decoders then use `writeValueAsString` + `zio-json` to re-parse into typed case
  * classes. This avoids duplicating zio-json derivation logic and keeps the wire format consistent
  * with the JVM path.
  */
final class JsMcpDecodeContext extends McpDecodeContext:

  override def convertValue[T: scala.reflect.ClassTag](name: String, rawValue: Any): T =
    // On JS we don't carry runtime class information reliably — but callers of `convertValue`
    // are almost always other McpDecoder implementations, not user code. A best-effort identity
    // cast is sufficient for primitives and `js.Any`. Prefer using `McpDecoder[T]` via zio-json
    // for anything structured.
    try rawValue.asInstanceOf[T]
    catch
      case e: ClassCastException =>
        throw new RuntimeException(
          s"Failed to convert value for parameter '$name'. Value: $rawValue",
          e
        )

  override def parseJsonArray(name: String, rawJson: String): List[Any] =
    try
      val parsed = JSON.parse(rawJson)
      parsed.asInstanceOf[js.Array[Any]].toList
    catch
      case e: Exception =>
        throw new RuntimeException(
          s"Failed to parse JSON array for parameter '$name'. Value: $rawJson",
          e
        )

  override def parseJsonObject(name: String, rawJson: String): Map[String, Any] =
    try
      val parsed = JSON.parse(rawJson)
      parsed.asInstanceOf[js.Dictionary[Any]].toMap
    catch
      case e: Exception =>
        throw new RuntimeException(
          s"Failed to parse JSON object for parameter '$name'. Value: $rawJson",
          e
        )

  override def writeValueAsString(value: Any): String =
    try JSON.stringify(JsMcpDecodeContext.toJs(value))
    catch
      case e: Exception =>
        throw new RuntimeException(s"Failed to write value as JSON. Value: $value", e)

object JsMcpDecodeContext:

  lazy val default: JsMcpDecodeContext = new JsMcpDecodeContext

  /** Walk a Scala-native value, turning `Map`s into `js.Dictionary`, `Iterable`s into `js.Array`,
    * and leaving primitives / `js.Any` untouched. Used by `writeValueAsString` so a Scala `Map`
    * hands over to `JSON.stringify` cleanly.
    */
  private[codec] def toJs(value: Any): js.Any =
    value match
      case null => null
      case s: String => s
      case b: Boolean => b
      case i: Int => i
      case l: Long => l.toDouble
      case f: Float => f.toDouble
      case d: Double => d
      case bytes: Array[Byte] => java.util.Base64.getEncoder.encodeToString(bytes)
      case opt: Option[?] => opt.map(toJs).getOrElse(null)
      case m: scala.collection.Map[?, ?] =>
        val dict = js.Dictionary.empty[js.Any]
        m.foreach { case (k, v) => dict.update(k.toString, toJs(v)) }
        dict.asInstanceOf[js.Any]
      case it: Iterable[?] =>
        js.Array[js.Any](it.toSeq.map(toJs)*).asInstanceOf[js.Any]
      case other =>
        // JS-native values (js.Dictionary, js.Array, js.Object, primitive numbers/strings, etc.)
        // pass through via `asInstanceOf` — `JSON.stringify` handles them directly. Scala objects
        // without a dedicated case above degenerate to `toString`, which is rarely ideal but at
        // least produces valid JSON output.
        other.asInstanceOf[js.Any]
