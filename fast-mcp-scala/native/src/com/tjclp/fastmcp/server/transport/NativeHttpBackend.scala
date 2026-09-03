package com.tjclp.fastmcp
package server.transport

/** Scala Native [[HttpTransportBackend]] — streamable HTTP over `java.net.ServerSocket`
  * ([[SocketHttpBackend]]) with session ids from `/dev/urandom` via [[NativeTransportBackend]].
  * zio-http has no Scala Native artifacts (upstream support is 4.x-milestoned, zio-http#2526), so
  * this hand-rolled backend is what makes `McpServerApp[Http]` and `runHttp()` compile and link on
  * this platform. Plaintext HTTP/1.1 only (`javax.net.ssl` is absent from the javalib): terminate
  * TLS at a proxy.
  *
  * ZIO signal handlers and shutdown hooks are silent no-ops on Scala Native, so a long-running
  * listener has no graceful SIGTERM path: the OS default action ends the process, which is fine for
  * a stateless-on-the-wire MCP server.
  */
object NativeHttpBackend extends SocketHttpBackend(NativeTransportBackend):

  /** The Scala Native HTTP seam, in the impl object so it's exportable. `ExportsNative` re-exports
    * this so `import com.tjclp.fastmcp.*` puts an `HttpTransportBackend` in scope and `runHttp()` /
    * `McpServerApp[Http, ...]` resolve.
    */
  given httpInstance: HttpTransportBackend = this
