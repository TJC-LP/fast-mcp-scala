package com.tjclp.fastmcp.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class FastMcpServerTest extends AnyFunSuite with Matchers {
  test("Server instantiation with default settings") {
    val server = new FastMcpServer()

    assert(server.name == "FastMCPScala")
  }

  test("Server instantiation with custom settings") {
    val settings = FastMcpServerSettings(
      port = 8080,
      host = "localhost"
    )
    val server = new FastMcpServer(name = "TestServer", settings = settings)

    assert(server.name == "TestServer")
  }
}
