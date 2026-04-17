package com.tjclp.fastmcp
package codec

import java.util.Base64

import zio.json.*

import com.tjclp.fastmcp.core.{Content, McpDecodeContext, McpDecoder, McpEncoder, TextContent}
import com.tjclp.fastmcp.server.McpContext

/** JS-platform default codecs.
  *
  *   - `given [T: JsonDecoder]: McpDecoder[T]` — round-trip the `rawValue` through JSON via the
  *     supplied `McpDecodeContext.writeValueAsString` and `zio-json`'s `JsonDecoder[T]`. This is
  *     the JS equivalent of Jackson's automatic `convertValue` on the JVM.
  *   - `given McpDecoder[McpContext]` — identity: the shared trait occasionally threads a
  *     context-parameter through the decoder chain, so we need a no-op decoder for it.
  *   - `given McpEncoder[Array[Byte]]` — base64 TextContent matching JVM behaviour.
  */
object JsMcpDecoders:

  given zioJsonDecoder[T](using decoder: JsonDecoder[T]): McpDecoder[T] with

    def decode(name: String, rawValue: Any, context: McpDecodeContext): T =
      val json = context.writeValueAsString(rawValue)
      decoder.decodeJson(json) match
        case Right(value) => value
        case Left(err) =>
          throw new RuntimeException(
            s"Failed to decode parameter '$name' from JSON: $err. Value: $json"
          )

  /** When a contract handler declares an `McpContext` parameter, the shared registration layer
    * threads the runtime context through this identity decoder so the typed-contract machinery
    * type-checks uniformly.
    */
  given McpDecoder[McpContext] with

    def decode(name: String, rawValue: Any, context: McpDecodeContext): McpContext =
      rawValue match
        case ctx: McpContext => ctx
        case other =>
          throw new RuntimeException(
            s"Expected McpContext for parameter '$name', got ${Option(other).map(_.getClass.getName).getOrElse("null")}"
          )

  /** Binary payloads encode as a single base64 `TextContent`, mirroring the JVM behaviour. */
  given McpEncoder[Array[Byte]] with

    def encode(value: Array[Byte]): List[Content] =
      List(TextContent(Base64.getEncoder.encodeToString(value)))
