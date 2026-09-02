package com.tjclp.fastmcp

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
export server.transport.NativeHttpBackend.given
