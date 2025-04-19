package com.tjclp.fastmcp.macros

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.{
  BooleanNode,
  DoubleNode,
  IntNode,
  TextNode,
  ArrayNode,
  ObjectNode,
  NullNode
}
import com.fasterxml.jackson.databind.ObjectMapper
import com.tjclp.fastmcp.server.McpContext

//class JacksonConverterTest extends AnyFunSuite with Matchers {
//  private val mapper = new ObjectMapper()
//
//  // Test static helper methods
//  test("JacksonConverter.toString should handle TextNode") {
//    val textNode = TextNode.valueOf("hello")
//    val result = JacksonConverter.toString(textNode)
//
//    assert(result == "hello")
//  }
//
//  test("JacksonConverter.toString should handle NullNode") {
//    val nullNode = NullNode.getInstance()
//    val result = JacksonConverter.toString(nullNode)
//
//    assert(result == null)
//  }
//
//  test("JacksonConverter.toBoolean should handle BooleanNode") {
//    val trueNode = BooleanNode.TRUE
//    val falseNode = BooleanNode.FALSE
//
//    assert(JacksonConverter.toBoolean(trueNode) == true)
//    assert(JacksonConverter.toBoolean(falseNode) == false)
//  }
//
//  test("JacksonConverter.toInt should handle IntNode") {
//    val intNode = IntNode.valueOf(42)
//    val result = JacksonConverter.toInt(intNode)
//
//    assert(result == 42)
//  }
//
//  test("JacksonConverter.toDouble should handle DoubleNode") {
//    val doubleNode = DoubleNode.valueOf(3.14)
//    val result = JacksonConverter.toDouble(doubleNode)
//
//    assert(result == 3.14)
//  }
//}
