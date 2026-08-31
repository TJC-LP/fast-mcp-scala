package com.tjclp.fastmcp
package codec

import java.util.Base64

import scala.compiletime.{constValue, constValueTuple}
import scala.deriving.Mirror

import zio.json.*

import com.tjclp.fastmcp.core.{Content, ImageContent, McpDecodeContext, McpDecoder, McpEncoder}
import com.tjclp.fastmcp.server.McpContext

/** Evidence that every case in an enum's mirrored element tuple is a singleton value. */
trait SingletonEnumValues[A, Cases <: Tuple]:
  def values: List[A]

object SingletonEnumValues:

  given empty[A]: SingletonEnumValues[A, EmptyTuple] with
    val values: List[A] = Nil

  given cons[A, Head <: A & Singleton, Tail <: Tuple](using
      head: ValueOf[Head],
      tail: SingletonEnumValues[A, Tail]
  ): SingletonEnumValues[A, Head *: Tail] with
    val values: List[A] = head.value :: tail.values

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

  /** String decoder for ordinary Scala 3 enums whose cases carry no constructor parameters. */
  inline given singletonEnumJsonDecoder[A <: scala.reflect.Enum](using
      mirror: Mirror.SumOf[A],
      enumValues: SingletonEnumValues[A, mirror.MirroredElemTypes]
  ): JsonDecoder[A] =
    val cases = constValueTuple[mirror.MirroredElemLabels].toList
      .map(_.toString)
      .zip(enumValues.values)
    JsonDecoder.string.mapOrFail { raw =>
      cases
        .find(_._1 == raw)
        .map(_._2)
        .toRight(s"Invalid ${constValue[mirror.MirroredLabel]} value '$raw'")
    }

  /** Mirror-derived fallback for case classes WITHOUT an explicit `JsonDecoder`.
    *
    * Deliberately constrained to `Mirror.ProductOf` (not `Mirror.Of`): sum types like `Option[T]`
    * and `Either[A, B]` also have mirrors, and `export McpDecoders.given` flattens this
    * low-priority given to the same precedence as `zioJsonDecoder` at root-import call sites — so
    * `McpDecoder[Option[String]]` used to resolve HERE, deriving a sum decoder that expects
    * `{"Some": ...}` and rejects bare values (#64). With the product constraint, sum types with
    * zio-json built-ins (Option, Either, collections) resolve uniquely through `zioJsonDecoder`.
    *
    * Derivation goes through [[macros.ZioJsonEnumDerivation]], which plants string-based
    * `JsonDecoder` locals for Scala 3 enum field types that have no user-supplied instance at the
    * call site (GH #78) — user-defined enum decoders always win.
    */
  inline given derivedZioJsonDecoder[T](using m: Mirror.ProductOf[T]): McpDecoder[T] =
    val jsonDecoder = macros.ZioJsonEnumDerivation.deriveDecoder[T](using m)
    new McpDecoder[T]:
      def decode(name: String, rawValue: Any, context: McpDecodeContext): T =
        val json = context.writeValueAsString(rawValue)
        jsonDecoder.decodeJson(json) match
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
