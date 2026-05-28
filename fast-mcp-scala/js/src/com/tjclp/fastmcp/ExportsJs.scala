package com.tjclp.fastmcp

/** JS-only additions to the shared export surface ([[com.tjclp.fastmcp.Exports]]).
  *
  * After the native-core rewrite the JS module contributes exactly one thing the shared surface
  * can't: the platform [[com.tjclp.fastmcp.server.transport.TransportBackend]] given (Bun.serve +
  * Node stdio). Everything else — `McpServer`, codecs, schema derivation, macros — is shared.
  */
export server.transport.JsTransportBackend.given
