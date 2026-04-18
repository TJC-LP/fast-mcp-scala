package com.tjclp.fastmcp
package server

/** Platform-neutral factory for [[McpServerCore]]. Each backend supplies a `given` instance — on
  * the JVM it builds a `FastMcpServer`, on Scala.js a `JsMcpServer`. Users don't interact with this
  * directly; the sugar trait [[McpServer]] and the `McpServer(...)` apply on each platform's
  * builders file both route through it.
  */
trait McpServerCoreFactory:
  def build(name: String, version: String, settings: McpServerSettings): McpServerCore

object McpServerCoreFactory:
  def apply(using f: McpServerCoreFactory): McpServerCoreFactory = f
