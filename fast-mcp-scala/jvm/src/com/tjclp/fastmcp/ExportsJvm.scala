package com.tjclp.fastmcp

/** JVM-only additions to the shared export surface ([[com.tjclp.fastmcp.Exports]]).
  *
  * After the native-core rewrite the JVM contributes exactly one thing the shared surface can't:
  * the platform [[com.tjclp.fastmcp.server.transport.TransportBackend]] given (ZIO HTTP +
  * `System.in`/`System.out`). Everything else — `McpServer`, codecs, schema derivation, macros —
  * is shared.
  */
export server.transport.JvmTransportBackend.given
