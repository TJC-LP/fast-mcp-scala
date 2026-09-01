package com.tjclp.fastmcp.server.transport

import zio.*

import com.tjclp.fastmcp.server.McpServerSettings
import com.tjclp.fastmcp.server.router.McpRouter

/** The stdio + platform-services half of the platform seam for the native core. HTTP serving lives
  * on the separate [[HttpTransportBackend]] so stdio-only applications (and their native images /
  * DCE'd JS bundles) never reach the HTTP server stack.
  *
  * Each platform supplies exactly one `given TransportBackend`: the JVM one drives
  * `System.in`-`System.out`; the JS one drives Node process IO. Everything else — schema, codecs,
  * JSON-RPC, router, built-ins, middleware — is shared. This is what lets a single `McpServer`
  * serve both platforms with identical behavior.
  *
  * `serveStdio` takes the fully-built [[McpRouter]] and runs forever (until interrupted). `R` is
  * the server environment; it threads straight through on the JVM (pure ZIO) and is captured into a
  * `Runtime[R]` at the process-IO boundary on JS.
  */
trait TransportBackend:

  /** Serve over stdio (NDJSON on stdin/stdout). Used by MCP stdio clients. */
  def serveStdio[R](router: McpRouter[R], settings: McpServerSettings): ZIO[R, Throwable, Unit]

  /** Cryptographically-secure random identifier (UUID v4 string). Session and task ids are bearer
    * handles, so they must be unguessable. Lives on the backend because shared code has no CSPRNG:
    * the JVM uses `java.util.UUID` (SecureRandom), JS uses Web Crypto, and Scala Native reads
    * `/dev/urandom` directly — on both non-JVM platforms `UUID.randomUUID` needs a `SecureRandom`
    * the runtime doesn't provide.
    */
  def randomId(): UIO[String]

object TransportBackend:
  def apply(using backend: TransportBackend): TransportBackend = backend
