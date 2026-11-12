package com.tjclp.fastmcp
package server.transport

import scala.annotation.experimental

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
  *
  * Request fibers are kept out of ZIO's `Fiber.roots` registry (`trackFiberRoots = false`):
  * registering a fiber allocates a `java.lang.ref.WeakReference`, and Scala Native 0.5's
  * weak-reference registry does not reclaim those at request rates — under sustained load the heap
  * grew by gigabytes per minute. The cost is that `Fiber.roots` / fiber dumps do not list in-flight
  * HTTP requests on this platform.
  *
  * EXPERIMENTAL, and marked `@experimental` so that using it is a deliberate choice: a program that
  * serves HTTP on Scala Native — `McpServerApp[Http]`, or `runHttp()` — compiles only with the
  * `-experimental` compiler flag (or from a definition that is itself `@experimental`). Stdio
  * programs never reference this object and are unaffected. The reason is a Scala Native runtime
  * defect, not this code: in 0.5.12 the large-object allocator can hand a racing thread a null
  * pointer, so concurrent requests or replies of 8 KiB and up can abort the process
  * (scala-native#4916, fixed on its main branch by #4935 but not yet released). See
  * docs/transports.md, "The socket backend".
  */
@experimental(
  "HTTP on Scala Native is experimental: Scala Native 0.5.12 can abort the process under concurrent payloads of 8 KiB and up (see docs/transports.md, \"The socket backend\"). Compile with -experimental to use it."
)
object NativeHttpBackend extends SocketHttpBackend(NativeTransportBackend, trackFiberRoots = false)
