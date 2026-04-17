package com.tjclp.fastmcp
package facades
package runtime

import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal

/** Minimal `@js.native` facade for Bun's runtime APIs.
  *
  * `Bun.serve({ port, hostname, fetch })` spawns an HTTP server that invokes `fetch` for each
  * incoming request and expects a Web-Standard `Response` in return. This is the shape our
  * Streamable-HTTP transport is designed for.
  *
  * Node/Deno parity is a deferred follow-up — the same `WebStandardStreamableHTTPServerTransport`
  * works under Node 18+ or Deno with an alternative HTTP listener (e.g. Hono, Deno.serve).
  */
@js.native
@JSGlobal("Bun")
object Bun extends js.Object:
  def serve(options: BunServeOptions): BunServer = js.native

/** Options accepted by `Bun.serve`. The `fetch` handler is the heart of it — it receives a
  * Web-Standard `Request` and must return a `Response` (or a Promise of one).
  */
trait BunServeOptions extends js.Object:
  val port: js.UndefOr[Int]
  val hostname: js.UndefOr[String]
  val fetch: js.Function1[js.Dynamic, js.Promise[js.Dynamic]]

object BunServeOptions:

  def apply(
      port: Int,
      hostname: String,
      fetch: js.Function1[js.Dynamic, js.Promise[js.Dynamic]]
  ): BunServeOptions =
    js.Dynamic
      .literal(port = port, hostname = hostname, fetch = fetch)
      .asInstanceOf[BunServeOptions]

/** Handle returned by `Bun.serve`. */
@js.native
trait BunServer extends js.Object:
  val port: Int = js.native
  val hostname: String = js.native
  val url: js.Dynamic = js.native
  def stop(): Unit = js.native

/** Facade for the Web-standard `crypto` global — we need `crypto.randomUUID()` for session id
  * generation. `java.util.UUID.randomUUID` would be natural but Scala.js's stub relies on
  * `java.security.SecureRandom`, which isn't available.
  */
@js.native
@JSGlobal("crypto")
object WebCrypto extends js.Object:
  def randomUUID(): String = js.native
