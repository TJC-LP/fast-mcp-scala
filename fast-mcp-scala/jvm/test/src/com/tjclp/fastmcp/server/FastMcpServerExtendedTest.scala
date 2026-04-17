package com.tjclp.fastmcp.server

import org.scalatest.funsuite.AnyFunSuite

/** Simplified tests for FastMcpServer to improve coverage */
class FastMcpServerExtendedTest extends AnyFunSuite {

  test("Server constructor with different parameters") {
    val server1 = new FastMcpServer()
    val server2 = new FastMcpServer("CustomName", "1.2.3")

    assert(server1.name == "FastMCPScala")
    assert(server2.name == "CustomName")
  }
}
