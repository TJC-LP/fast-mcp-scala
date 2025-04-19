package com.tjclp.fastmcp.server

import io.modelcontextprotocol.spec.McpSchema
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import reactor.core.publisher.Mono
import zio.*
import scala.jdk.CollectionConverters.*

/** Simple unit tests for the internal ZIO ⇢ Reactor bridge helpers contained in FastMcpServer.
  *
  * These helpers are package‑private (private[server]) so we can call them directly without using
  * reflection. We do *not* spin up a full server – just instantiate the class and run the methods
  * in isolation.
  */
class FastMcpServerZioMonoSpec extends AnyFlatSpec with Matchers {

  private val server = new FastMcpServer()

  "zioToMono" should "convert successful ZIO effects to Mono with the same result" in {
    val mono: Mono[Int] = server.zioToMono(ZIO.succeed(42))
    mono.block() shouldBe 42
  }

  it should "propagate failures as Mono errors" in {
    val mono: Mono[Int] = server.zioToMono(ZIO.fail(new RuntimeException("boom")))
    an[RuntimeException] should be thrownBy mono.block()
  }

  "zioToMonoWithErrorHandling" should "wrap success into CallToolResult with isError=false" in {
    val mono: Mono[McpSchema.CallToolResult] = server.zioToMonoWithErrorHandling[Int](
      ZIO.succeed(7),
      _ => new McpSchema.CallToolResult("seven", false)
    )
    val result = mono.block()
    result.isError() shouldBe false
  }

  it should "convert ZIO failures into CallToolResult with isError=true" in {
    val mono: Mono[McpSchema.CallToolResult] = server.zioToMonoWithErrorHandling[Int](
      ZIO.fail(new IllegalArgumentException("bad args")),
      _ => new McpSchema.CallToolResult("dummy", false)
    )
    val result = mono.block()
    result.isError() shouldBe true
    // Message is lower‑cased by ErrorMapper, so just check substring
    import scala.jdk.CollectionConverters.*
    val textContent = result.content().asScala.head.asInstanceOf[McpSchema.TextContent]
    textContent.text() shouldBe "bad args"
  }
}
