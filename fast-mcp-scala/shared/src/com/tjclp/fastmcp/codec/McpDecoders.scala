package com.tjclp.fastmcp
package codec

import java.util.Base64

import scala.deriving.Mirror

import zio.json.*

import com.tjclp.fastmcp.core.{Content, ImageContent, McpDecodeContext, McpDecoder, McpEncoder}
import com.tjclp.fastmcp.server.McpContext

/** The single, platform-neutral default codec set for the native core.
  *
  * Promoted from the old JS-only `JsMcpDecoders` (which was already pure zio-json + Scala stdlib —
  * no JS specifics) to `shared/`, so JVM and Scala.js share one decode surface. Pairs with
  * [[DefaultDecodeContext]]. This replaces the deleted JVM `JacksonConverter` derivation.
  *
  *   - `given [T: JsonDecoder]: McpDecoder[T]` — re-serialize `rawValue` via the context and decode
  *     with zio-json. Equivalent to Jackson's old automatic `convertValue` on the JVM.
  *   - low-priority `Mirror`-based fallback for case classes without an explicit `JsonDecoder`.
  *   - `given McpDecoder[McpContext]` — identity, for context-threading in the contract layer.
  *   - `given McpEncoder[Array[Byte]]` — binary → `ImageContent("application/octet-stream")`.
  */
trait McpDecodersLowPriority:

  /** Mirror-derived fallback for case classes WITHOUT an explicit `JsonDecoder`.
    *
    * Deliberately constrained to `Mirror.ProductOf` (not `Mirror.Of`): sum types like `Option[T]`
    * and `Either[A, B]` also have mirrors, and `export McpDecoders.given` flattens this
    * low-priority given to the same precedence as `zioJsonDecoder` at root-import call sites — so
    * `McpDecoder[Option[String]]` used to resolve HERE, deriving a sum decoder that expects
    * `{"Some": ...}` and rejects bare values (#64). With the product constraint, sum types with
    * zio-json built-ins (Option, Either, collections) resolve uniquely through `zioJsonDecoder`.
    */
  inline given derivedZioJsonDecoder[T](using Mirror.ProductOf[T]): McpDecoder[T] =
    new McpDecoder[T]:
      def decode(name: String, rawValue: Any, context: McpDecodeContext): T =
        val json = context.writeValueAsString(rawValue)
        DeriveJsonDecoder.gen[T].decodeJson(json) match
          case Right(value) => value
          case Left(err) =>
            throw new RuntimeException(
              s"Failed to decode parameter '$name' from JSON: $err. Value: $json"
            )

object McpDecoders extends McpDecodersLowPriority:

  given zioJsonDecoder[T](using decoder: JsonDecoder[T]): McpDecoder[T] with

    def decode(name: String, rawValue: Any, context: McpDecodeContext): T =
      val json = context.writeValueAsString(rawValue)
      decoder.decodeJson(json) match
        case Right(value) => value
        case Left(err) =>
          throw new RuntimeException(
            s"Failed to decode parameter '$name' from JSON: $err. Value: $json"
          )

  /** Identity decoder so the contract layer can thread the runtime context uniformly. */
  given McpDecoder[McpContext] with

    def decode(name: String, rawValue: Any, context: McpDecodeContext): McpContext =
      rawValue match
        case ctx: McpContext => ctx
        case other =>
          throw new RuntimeException(
            s"Expected McpContext for parameter '$name', got ${Option(other).map(_.getClass.getName).getOrElse("unknown")}"
          )

  /** Binary payloads encode as a single `ImageContent`. */
  given McpEncoder[Array[Byte]] with

    def encode(value: Array[Byte]): List[Content] =
      List(
        ImageContent(
          data = Base64.getEncoder.encodeToString(value),
          mimeType = "application/octet-stream"
        )
      )
