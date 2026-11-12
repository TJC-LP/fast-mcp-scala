package com.tjclp.fastmcp
package interop

import scala.scalajs.js
import scala.scalajs.js.JavaScriptException

import zio.*

/** Bidirectional bridge between ZIO effects and JavaScript Promises.
  *
  * Used by the Scala.js transport to:
  *   - turn ZIO effects into `js.Promise`s the Bun/Web platform APIs `await` (`Bun.serve` fetch,
  *     `ReadableStream` pull/cancel)
  *   - adapt incoming `js.Promise` results (`fetch`, `Request.text()`) into ZIO effects
  */
object ZioJsPromise:

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
    // The fiber settles the promise directly from its exit observer. Going through
    // `runToFuture` + `Future.onComplete` put a Scala `Future` and a trip through the global
    // execution context between every effect and its promise — on every HTTP request and on
    // every stream pull.
    new js.Promise[A]((resolve, reject) =>
      Unsafe.unsafe { implicit unsafe =>
        runtime.unsafe.fork(effect).unsafe.addObserver {
          case Exit.Success(a) => val _ = resolve(a)
          case Exit.Failure(cause) =>
            // The same Throwable `runToFuture` would have failed its Future with. Unwrap
            // JavaScriptException so the JS caller sees the underlying error (e.g. an `McpError`
            // thrown from a handler). Other Throwables reject as-is.
            val rejectValue: scala.Any = cause.squashTraceWith(identity) match
              case JavaScriptException(inner) => inner
              case other => other
            val _ = reject(rejectValue)
        }
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
