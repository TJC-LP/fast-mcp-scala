package com.tjclp.fastmcp.server.transport

import zio.*

import com.tjclp.fastmcp.server.McpServerSettings
import com.tjclp.fastmcp.server.router.McpRouter

/** The HTTP half of the platform seam, split from [[TransportBackend]] so applications that only
  * ever call `runStdio()` never reference the HTTP server stack.
  *
  * The split is structural on purpose: `runHttp()` takes this as a `using` parameter and the
  * `TransportRunner[Http]` given is conditional on it, so a stdio-only program has no reachable
  * call path into `serveHttp`. Under closed-world compilation (GraalVM native-image, Scala.js DCE)
  * that keeps the entire HTTP driver — zio-http and Netty on the JVM — out of the binary.
  *
  * Each platform supplies exactly one given: [[JvmHttpBackend]] (ZIO HTTP / Netty) and the JS
  * backend (`Bun.serve`). Like [[TransportBackend]], implementations take the fully-built
  * [[McpRouter]] and run forever (until interrupted).
  *
  * Hardening contract every implementation must honour (the decisions live in the shared, pure
  * [[HttpRequestGuards]] / [[HostGuard]]; backends only render the verdicts):
  *   - run `HttpRequestGuards.validateSettings(settings)` before binding and fail startup on
  *     `Left`;
  *   - bound the request body at `settings.maxRequestBodyBytes` in the platform server AND apply
  *     `postGate` (403 → 415 → 413 → 406) before reading the body or touching session state, then
  *     `bodyTooLarge` before `MessageLoop.parseFrame`;
  *   - `hostGate` on GET/DELETE;
  *   - admit legacy `initialize` through `capReached` / `pickEvictable` (evict the longest-idle
  *     session without a live GET, else 503 `SessionLimitMessage`);
  *   - run the idle-session sweeper for the listener's whole lifetime on every start entry;
  *   - wrap each handler in a Cause → JSON-RPC 500 (`InternalErrorMessage`) boundary that logs the
  *     cause server-side only and never renders exception text, traces or paths to the client.
  */
trait HttpTransportBackend:

  /** Serve over HTTP. `settings.stateless` selects stateless request/response vs the streamable
    * transport (sessions + SSE).
    */
  def serveHttp[R](router: McpRouter[R], settings: McpServerSettings): ZIO[R, Throwable, Unit]

object HttpTransportBackend:
  def apply(using backend: HttpTransportBackend): HttpTransportBackend = backend
