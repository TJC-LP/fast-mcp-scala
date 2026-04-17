package com.tjclp.fastmcp
package interop

import scala.concurrent.ExecutionContext
import scala.scalajs.js
import scala.scalajs.js.JavaScriptException
import scala.util.Failure
import scala.util.Success

import zio.*

/** Bidirectional bridge between ZIO effects and JavaScript Promises.
  *
  * Used by the Scala.js MCP runtime to:
  *   - turn ZIO handlers into `js.Promise`s the TS SDK can `await`
  *   - adapt incoming `js.Promise` results (from TS SDK facades) into ZIO effects
  */
object ZioJsPromise:

  private given ExecutionContext = scala.concurrent.ExecutionContext.global

  /** Run a `ZIO[Any, Throwable, A]` on the default ZIO runtime and expose the result as a
    * `js.Promise[A]`.
    */
  def zioToPromise[A](effect: ZIO[Any, Throwable, A]): js.Promise[A] =
    val future = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.runToFuture(effect)
    }
    new js.Promise[A]((resolve, reject) =>
      future.onComplete {
        case Success(a) => val _ = resolve(a)
        case Failure(t) =>
          // Unwrap JavaScriptException so the JS caller sees the underlying error (e.g. an
          // `McpError` thrown from a handler). Other Throwables reject as-is.
          val rejectValue: scala.Any = t match
            case JavaScriptException(inner) => inner
            case other => other
          val _ = reject(rejectValue)
      }
    )

  /** Wrap a `js.Promise[A]` as a `ZIO[Any, Throwable, A]`. JavaScript rejections that are not
    * `Throwable`s are wrapped in `JavaScriptException`.
    */
  def fromJsPromise[A](thunk: => js.Promise[A]): ZIO[Any, Throwable, A] =
    ZIO.async { cb =>
      val _ = thunk.`then`[Unit](
        (value: A) => { cb(ZIO.succeed(value)); () },
        (err: scala.Any) =>
          val throwable = err match
            case t: Throwable => t
            case other => JavaScriptException(other)
          cb(ZIO.fail(throwable))
          ()
      )
    }
