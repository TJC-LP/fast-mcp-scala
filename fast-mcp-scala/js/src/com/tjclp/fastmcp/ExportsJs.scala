package com.tjclp.fastmcp

/** JS-only additions to the shared export surface ([[com.tjclp.fastmcp.Exports]]).
  *
  * After the native-core rewrite the JS module contributes exactly one thing the shared surface
  * can't: the platform transport givens — [[com.tjclp.fastmcp.server.transport.TransportBackend]]
  * (Node stdio) and [[com.tjclp.fastmcp.server.transport.HttpTransportBackend]] (Bun.serve), both
  * provided by `JsTransportBackend`. Everything else — `McpServer`, codecs, schema derivation,
  * macros — is shared.
  */
export server.transport.JsTransportBackend.given
