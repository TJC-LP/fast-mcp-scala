package com.tjclp.fastmcp.macros.schema

import org.scalatest.funsuite.AnyFunSuite
import sttp.tapir.generic.auto.*

import com.tjclp.fastmcp.macros.JsonSchemaMacro

/** Simple test for SchemaGenerator to improve coverage */
class SchemaGeneratorExtendedTest extends AnyFunSuite {

  // Test objects
  case class SimpleUser(id: String, name: String)

  // Just verify schema generation works
  test("Should generate schema for simple types") {
    def processUser(user: SimpleUser): Unit = ()

    val schema = JsonSchemaMacro.schemaForFunctionArgs(processUser)
    assert(schema.isObject)
  }
}
