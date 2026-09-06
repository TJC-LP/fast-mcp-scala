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
  * Web-Standard `Request` plus the `Server` (Bun calls `fetch(request, server)`) and must return a
  * `Response` (or a Promise of one).
  *
  * Hardening knobs (all set by the transport, never left to Bun's defaults):
  *   - `maxRequestBodySize` — bytes; Bun's own default is 128 MiB. Over-cap bodies are answered 413
  *     by Bun itself (declared or streamed) before / while `fetch` runs.
  *   - `development = false` — Bun otherwise defaults to `NODE_ENV !== "production"` and renders a
  *     ~100 KB HTML debug page (stack traces, on-disk paths) for a rejected `fetch` promise.
  *   - `error` — last-resort callback for anything Bun still sees; returns a plain JSON 500.
  */
trait BunServeOptions extends js.Object:
  val port: js.UndefOr[Int]
  val hostname: js.UndefOr[String]
  val fetch: js.Function2[js.Dynamic, js.Dynamic, js.Promise[js.Dynamic]]
  val maxRequestBodySize: js.UndefOr[Int]
  val development: js.UndefOr[Boolean]
  val error: js.UndefOr[js.Function1[js.Dynamic, js.Dynamic]]

object BunServeOptions:

  def apply(
      port: Int,
      hostname: String,
      fetch: js.Function2[js.Dynamic, js.Dynamic, js.Promise[js.Dynamic]],
      maxRequestBodySize: Int,
      development: Boolean,
      error: js.Function1[js.Dynamic, js.Dynamic]
  ): BunServeOptions =
    js.Dynamic
      .literal(
        port = port,
        hostname = hostname,
        fetch = fetch,
        maxRequestBodySize = maxRequestBodySize,
        development = development,
        error = error
      )
      .asInstanceOf[BunServeOptions]

/** Handle returned by `Bun.serve`. */
@js.native
trait BunServer extends js.Object:
  val port: Int = js.native
  val hostname: String = js.native
  val url: js.Dynamic = js.native
  def stop(): Unit = js.native

  /** Peer socket address of a request — `{ address, port, family }` — or `null` once the socket is
    * gone. The transport uses `address` as the default bearer-task owner key.
    */
  def requestIP(request: js.Dynamic): js.Dynamic = js.native

/** Facade for the Web-standard `crypto` global — we need `crypto.randomUUID()` for session id
  * generation. `java.util.UUID.randomUUID` would be natural but Scala.js's stub relies on
  * `java.security.SecureRandom`, which isn't available.
  */
@js.native
@JSGlobal("crypto")
object WebCrypto extends js.Object:
  def randomUUID(): String = js.native
