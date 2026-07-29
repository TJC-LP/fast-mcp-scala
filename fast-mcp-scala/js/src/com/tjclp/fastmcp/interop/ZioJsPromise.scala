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
  * Used by the Scala.js transport to:
  *   - turn ZIO effects into `js.Promise`s the Bun/Web platform APIs `await` (`Bun.serve` fetch,
  *     `ReadableStream` pull/cancel)
  *   - adapt incoming `js.Promise` results (`fetch`, `Request.text()`) into ZIO effects
  */
object ZioJsPromise:

  private given ExecutionContext = scala.concurrent.ExecutionContext.global

  /** Run a `ZIO[Any, Throwable, A]` on the default ZIO runtime and expose the result as a
    * `js.Promise[A]`.
    */
  def zioToPromise[A](effect: ZIO[Any, Throwable, A]): js.Promise[A] =
    zioToPromise[Any, A](Runtime.default)(effect)

  /** Run a `ZIO[R, Throwable, A]` on the supplied runtime and expose the result as a
    * `js.Promise[A]`. Used by the JS transport to thread the runtime captured at `runHttp[R]` /
    * `runStdio[R]` entry into each handler invocation, so user-supplied layers reach the effects
    * that need them.
    */
  def zioToPromise[R, A](runtime: Runtime[R])(effect: ZIO[R, Throwable, A]): js.Promise[A] =
    val future = Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.runToFuture(effect)
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
