package com.tjclp.fastmcp
package server.router

import zio.*

import com.tjclp.fastmcp.jsonrpc.McpError

/** Where the `tools/call` being dispatched stands with respect to becoming a task, carried on the
  * dispatching fiber for [[com.tjclp.fastmcp.server.McpContext.continueAsTask]].
  *
  * A tool registered with `McpTool.withDeferredTask` starts every call as an ordinary request: its
  * handler gathers input over MRTR (`input_required` results on the request itself) and then calls
  * `continueAsTask`. On the request that call ends the attempt with a private signal
  * ([[McpError.taskHandoff]]); the task middleware creates the task and runs the handler again
  * inside it, where the same call returns `true` and the handler carries on. That is the replay
  * MRTR already uses, applied to the hand-off (SEP-2663, "Composition with Multi Round-Trip
  * Requests").
  *
  * One process-wide `FiberRef`, like [[RequestHeaders]]: fibers a handler forks inherit the value
  * they were forked with, so the answer is the same in every branch of one attempt.
  */
private[fastmcp] object TaskHandoff:

  enum Phase:

    /** The call cannot become a task from here: `continueAsTask` returns `false`. */
    case Unavailable

    /** The call is still a request and may become a task: `continueAsTask` ends the attempt. */
    case Offered

    /** The handler is running as a task: `continueAsTask` returns `true`. */
    case InTask

  private val ref: FiberRef[Phase] =
    Unsafe.unsafe(implicit u => FiberRef.unsafe.make(Phase.Unavailable))

  /** Run a request-phase attempt of a tool whose task creation is deferred. */
  def offered[R, E, A](zio: ZIO[R, E, A]): ZIO[R, E, A] = ref.locally(Phase.Offered)(zio)

  /** Run a task body. */
  def inTask[R, E, A](zio: ZIO[R, E, A]): ZIO[R, E, A] = ref.locally(Phase.InTask)(zio)

  /** What `McpContext.continueAsTask` does on the current fiber. */
  val continue: IO[McpError, Boolean] =
    ref.get.flatMap {
      case Phase.Unavailable => ZIO.succeed(false)
      case Phase.InTask => ZIO.succeed(true)
      case Phase.Offered => ZIO.fail(McpError.taskHandoff)
    }
