package com.tjclp.fastmcp
package server

import zio.*

/** Type-level MCP transport marker.
  *
  * Used as a phantom type parameter on the sugar trait [[com.tjclp.fastmcp.server.McpServer]] so
  * the transport choice is compile-time checked. Actual runtime behavior is resolved via a
  * [[TransportRunner]] given selected on the marker.
  *
  * A future transport (e.g. `WebSocket`) slots in as a new `case object` plus a new `given
  * TransportRunner[WebSocket]` — no signature churn elsewhere.
  */
sealed trait Transport

object Transport:
  sealed trait Stdio extends Transport
  sealed trait Http extends Transport
  case object Stdio extends Stdio
  case object Http extends Http

type Stdio = Transport.Stdio
type Http = Transport.Http

/** Typeclass that maps a [[Transport]] marker to the effectful runner that starts the server on
  * that transport. Platform-neutral — each given just delegates to the relevant method on
  * [[McpServerCore]].
  */
trait TransportRunner[T <: Transport]:
  def run(core: McpServerCore): ZIO[Any, Throwable, Unit]

object TransportRunner:

  given stdio: TransportRunner[Stdio] with
    def run(core: McpServerCore): ZIO[Any, Throwable, Unit] = core.runStdio()

  given http: TransportRunner[Http] with
    def run(core: McpServerCore): ZIO[Any, Throwable, Unit] = core.runHttp()
