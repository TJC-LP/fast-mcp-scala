package com.tjclp.fastmcp

import scala.annotation.experimental

/** Scala Native additions to the shared export surface ([[com.tjclp.fastmcp.Exports]]).
  *
  * The native module contributes exactly two things the shared surface can't: the platform
  * [[com.tjclp.fastmcp.server.transport.TransportBackend]] given (`System.in`/`System.out`) and the
  * [[com.tjclp.fastmcp.server.transport.HttpTransportBackend]] given — the hand-rolled
  * `java.net.ServerSocket` backend, since zio-http is not published for Scala Native. They are
  * separate objects so stdio-only programs never reach the HTTP server stack (see
  * `HttpTransportBackend`'s scaladoc). Everything else — `McpServer`, codecs, schema derivation,
  * macros — is shared.
  */
export server.transport.NativeTransportBackend.given

/** The Scala Native HTTP backend as the root-import given, so `McpServerApp[Http]` and `runHttp()`
  * resolve it — in experimental mode only.
  *
  * Declared here as an `@experimental` given rather than re-exported with `export
  * NativeHttpBackend.given`: an export forwarder does not carry the `@experimental` marker of the
  * definition it forwards to, so a re-export would let stable code serve HTTP on Scala Native
  * without ever opting in. `NativeStdioStaysStableTest` and the compile check in
  * `docs/transports.md` rely on this being the only route to the backend.
  */
@experimental(
  "HTTP on Scala Native is experimental: Scala Native 0.5.12 can abort the process under concurrent payloads of 8 KiB and up (see docs/transports.md, \"The socket backend\"). Compile with -experimental to use it."
)
given httpInstance: server.transport.HttpTransportBackend = server.transport.NativeHttpBackend
