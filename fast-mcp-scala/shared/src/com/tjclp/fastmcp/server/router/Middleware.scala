package com.tjclp.fastmcp.server.router

import zio.*
import zio.json.ast.Json

import com.tjclp.fastmcp.jsonrpc.McpError

/** A request handler: given the session and the raw `params` JSON, produce a result JSON or fail
  * with a protocol [[McpError]]. This is the single shape every built-in (initialize, tools/call,
  * …) and every middleware composes around.
  */
type RequestHandler[R] = (Session, Json) => ZIO[R, McpError, Json]

/** A notification handler: no response, failures are logged not returned. */
type NotificationHandler[R] = (Session, Json) => ZIO[R, McpError, Unit]

/** A request-pipeline stage. Middlewares compose around the terminal handler in registration
  * order: the first-listed middleware is outermost (runs first on the way in, last on the way
  * out). The canonical chain is validation → task augmentation → handler → error mapping.
  */
trait Middleware[R]:
  def wrap(method: String, next: RequestHandler[R]): RequestHandler[R]

object Middleware:

  /** Fold a list of middlewares around a terminal handler. Head = outermost. */
  def chain[R](middlewares: List[Middleware[R]], method: String, terminal: RequestHandler[R]): RequestHandler[R] =
    middlewares.foldRight(terminal)((mw, acc) => mw.wrap(method, acc))

/** Observability hooks fired around tool dispatch and on protocol errors. Default no-ops; the
  * server wires concrete implementations (logging emit, metrics, tracing). `R` lets a hook reach
  * the server environment. Failures in hooks must not break dispatch, hence the `Nothing` error.
  */
trait ServerHooks[R]:
  def beforeToolCall(name: String, args: Json, session: Session): ZIO[R, Nothing, Unit] =
    ZIO.unit
  def afterToolCall(name: String, result: Json, session: Session): ZIO[R, Nothing, Unit] =
    ZIO.unit
  def onError(method: String, error: McpError, session: Session): ZIO[R, Nothing, Unit] =
    ZIO.unit

object ServerHooks:
  /** A hooks instance that does nothing — the default. */
  def noop[R]: ServerHooks[R] = new ServerHooks[R] {}
