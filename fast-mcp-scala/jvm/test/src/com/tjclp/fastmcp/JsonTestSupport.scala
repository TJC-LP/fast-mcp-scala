package com.tjclp.fastmcp

import zio.json.*
import zio.json.ast.Json

/** Minimal test navigation helpers used by schema assertions. */
object JsonTestSupport:
  def parse(value: String): Either[String, Json] = value.fromJson[Json]

  extension (json: Json)
    def hcursor: Cursor = Cursor(Some(json))
    def isObject: Boolean = json.asObject.isDefined
    def isNull: Boolean = json.asNull.isDefined

  final case class Cursor(focus: Option[Json]):
    def downField(name: String): Cursor =
      Cursor(focus.flatMap(_.asObject).flatMap(_.get(name)))

    def as[A: JsonDecoder]: Either[String, A] =
      focus.toRight("JSON cursor has no focus").flatMap(_.as[A])

    def keys: Option[Iterable[String]] =
      focus.flatMap(_.asObject).map(_.keys)

    def succeeded: Boolean = focus.isDefined
    def failed: Boolean = focus.isEmpty
