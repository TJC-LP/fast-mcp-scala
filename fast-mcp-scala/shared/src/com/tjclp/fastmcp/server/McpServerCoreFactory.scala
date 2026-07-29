package com.tjclp.fastmcp
package server

/** Platform-neutral factory for [[McpServerCore]]. ONE `given` instance exists, defined in the
  * shared [[McpServer]] companion (it needs only a `TransportBackend` in scope); the sugar trait
  * [[McpServerApp]] routes through it. Users don't interact with this directly.
  *
  * Returns `McpServerCore[Any]` because this is the default, layer-free server. Users who need `R
  * != Any` build an `McpServer.typed[R]` directly.
  */
trait McpServerCoreFactory:
  def build(name: String, version: String, settings: McpServerSettings): McpServerCore[Any]

object McpServerCoreFactory:
  def apply(using f: McpServerCoreFactory): McpServerCoreFactory = f
