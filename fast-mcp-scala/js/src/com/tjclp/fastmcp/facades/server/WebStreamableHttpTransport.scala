package com.tjclp.fastmcp
package facades
package server

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/** Scala.js facade for `WebStandardStreamableHTTPServerTransport` — the Bun / Deno / Node-18+
  * friendly Streamable HTTP server transport backed by Web Standard `Request`/`Response` APIs.
  *
  * Stateful mode: pass a `sessionIdGenerator` (e.g. `() => crypto.randomUUID()`). The SDK manages
  * per-session SSE streams for long-running operations.
  *
  * Stateless mode: leave `sessionIdGenerator = js.undefined` and set `enableJsonResponse = true`;
  * each request produces a fresh JSON response with no SSE. A stateless transport instance can only
  * handle a single request — spin up a new one per request on the hot path.
  */
@JSImport(
  "@modelcontextprotocol/sdk/server/webStandardStreamableHttp.js",
  "WebStandardStreamableHTTPServerTransport"
)
@js.native
class WebStandardStreamableHttpServerTransport(
    options: WebStreamableHttpOptions = js.Dynamic.literal().asInstanceOf[WebStreamableHttpOptions]
) extends Transport:
  var sessionId: js.UndefOr[String] = js.native

  /** Handle a single incoming HTTP request. Returns a Web-Standard `Response` the runtime can ship
    * back to the client.
    */
  def handleRequest(
      req: js.Dynamic,
      options: js.UndefOr[js.Dynamic] = js.undefined
  ): js.Promise[js.Dynamic] = js.native

  def start(): js.Promise[Unit] = js.native
  def close(): js.Promise[Unit] = js.native

trait WebStreamableHttpOptions extends js.Object:
  val sessionIdGenerator: js.UndefOr[js.Function0[String]]
  val enableJsonResponse: js.UndefOr[Boolean]
  val onsessioninitialized: js.UndefOr[js.Function1[String, js.Any]]
  val onsessionclosed: js.UndefOr[js.Function1[String, js.Any]]
  val enableDnsRebindingProtection: js.UndefOr[Boolean]
  val allowedHosts: js.UndefOr[js.Array[String]]

object WebStreamableHttpOptions:

  /** Stateful (session + SSE) configuration. */
  def stateful(sessionIdGenerator: () => String): WebStreamableHttpOptions =
    js.Dynamic
      .literal(
        sessionIdGenerator = js.Any.fromFunction0(sessionIdGenerator)
      )
      .asInstanceOf[WebStreamableHttpOptions]

  /** Stateless (JSON-response-only) configuration. */
  def stateless: WebStreamableHttpOptions =
    js.Dynamic
      .literal(
        enableJsonResponse = true
      )
      .asInstanceOf[WebStreamableHttpOptions]
