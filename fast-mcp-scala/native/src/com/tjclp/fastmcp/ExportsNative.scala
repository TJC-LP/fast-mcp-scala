package com.tjclp.fastmcp

/** Scala Native additions to the shared export surface ([[com.tjclp.fastmcp.Exports]]).
  *
  * The native module contributes exactly one thing the shared surface can't: the platform
  * [[com.tjclp.fastmcp.server.transport.TransportBackend]] given (`System.in`/`System.out`). There
  * is deliberately NO `HttpTransportBackend` — zio-http is not published for Scala Native — so
  * `McpServerApp[Http]` programs fail to compile on this platform while `McpServerApp[Stdio]` works
  * unchanged. Everything else — `McpServer`, codecs, schema derivation, macros — is shared.
  */
export server.transport.NativeTransportBackend.given
