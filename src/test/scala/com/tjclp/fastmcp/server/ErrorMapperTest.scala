package com.tjclp.fastmcp.server

import io.modelcontextprotocol.spec.McpSchema
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.TimeoutException
import scala.jdk.CollectionConverters.*

class ErrorMapperTest extends AnyFunSuite with Matchers {
  test("toCallToolResult should create result with error flag set to true") {
    val exception = new RuntimeException("test error")
    val result = ErrorMapper.toCallToolResult(exception)
    val content = result.content().asScala
    val text = content.head.asInstanceOf[McpSchema.TextContent].text()
    assert(text == "test error")
    assert(result.isError())
  }

  test("toCallToolResult should use class name when message is null") {
    val exceptionWithNullMessage = new RuntimeException()
    val result = ErrorMapper.toCallToolResult(exceptionWithNullMessage)
    val content = result.content().asScala
    val text = content.head.asInstanceOf[McpSchema.TextContent].text()
    assert(text == "Error: RuntimeException")
    assert(result.isError())
  }

  test("errorMessage should handle TimeoutException") {
    val exception = new TimeoutException("operation took too long")
    val message = ErrorMapper.errorMessage(exception)

    assert(message == "Operation timed out: operation took too long")
  }

  test("errorMessage should handle IllegalArgumentException") {
    val exception = new IllegalArgumentException("invalid input")
    val message = ErrorMapper.errorMessage(exception)

    assert(message == "Invalid argument: invalid input")
  }

  test("errorMessage should handle NoSuchElementException") {
    val exception = new NoSuchElementException("item not found")
    val message = ErrorMapper.errorMessage(exception)

    assert(message == "Not found: item not found")
  }

  test("errorMessage should handle generic exceptions") {
    val exception = new RuntimeException("something went wrong")
    val message = ErrorMapper.errorMessage(exception)

    assert(message == "something went wrong")
  }

  test("errorMessage should handle exceptions with null message") {
    val exceptionWithNullMessage = new RuntimeException()
    val message = ErrorMapper.errorMessage(exceptionWithNullMessage)

    assert(message == "Internal error: RuntimeException")
  }
}
