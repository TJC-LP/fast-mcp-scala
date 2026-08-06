package com.tjclp.fastmcp.core.wire

import zio.json.*
import zio.json.ast.Json

/** A filesystem root the client exposes (returned by the server-initiated `roots/list` request).
  * Per spec the `uri` is a `file://` URI.
  */
case class Root(
    uri: String,
    name: Option[String] = None,
    _meta: Option[Map[String, Json]] = None
)

object Root:
  given JsonCodec[Root] = DeriveJsonCodec.gen[Root]

/** Result of the server-initiated `roots/list` request. */
case class ListRootsResult(
    roots: List[Root],
    _meta: Option[Map[String, Json]] = None
)

object ListRootsResult:
  given JsonCodec[ListRootsResult] = DeriveJsonCodec.gen[ListRootsResult]
