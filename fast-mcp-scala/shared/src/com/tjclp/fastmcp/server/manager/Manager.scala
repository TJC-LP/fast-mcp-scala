package com.tjclp.fastmcp.server.manager

/** Base trait for all manager classes in the FastMCP-Scala implementation
  */
trait Manager[DefinitionType]:
  /** Returns a list of all registered definitions
    */
  def listDefinitions(): List[DefinitionType]
