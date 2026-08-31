package com.tjclp.fastmcp

/** JVM-only additions to the shared export surface ([[com.tjclp.fastmcp.Exports]]).
  *
  * After the native-core rewrite the JVM contributes exactly two things the shared surface can't:
  * the platform [[com.tjclp.fastmcp.server.transport.TransportBackend]] given
  * (`System.in`/`System.out`) and the [[com.tjclp.fastmcp.server.transport.HttpTransportBackend]]
  * given (ZIO HTTP). They are separate objects so stdio-only programs never reach the HTTP stack
  * (see `HttpTransportBackend`'s scaladoc). Everything else — `McpServer`, codecs, schema
  * derivation, macros — is shared.
  */
export server.transport.JvmTransportBackend.given
export server.transport.JvmHttpBackend.given
