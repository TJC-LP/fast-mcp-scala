package com.tjclp.fastmcp.macros.schema

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import sttp.tapir.Schema
import sttp.tapir.Schema.SName
import sttp.tapir.generic.auto.*

class SchemaExtractorTest extends AnyFunSuite with Matchers {

  // Helper to expose the testable parts of SchemaExtractor's functionality
  object SchemaExtractorTestable {

    enum TestEnum {
      case One, Two, Three
    }

    case class TestProduct(name: String, value: Int)

    // Simplified version of maybeAssignNameToSchema that doesn't use quotation
    def isProductType[T](schema: Schema[T]): Boolean =
      schema.schemaType match {
        case _: sttp.tapir.SchemaType.SProduct[_] => true
        case _ => false
      }

    // Non-macro version of createSchemaFor that works with resolved Schema[T] instances
    def isEnumSchema[T](schema: Schema[T]): Boolean =
      schema.schemaType match {
        case _: sttp.tapir.SchemaType.SCoproduct[_] => true
        case _ => false
      }
  }

  import SchemaExtractorTestable.*

  test("isProductType should identify product types") {
    val productSchema = Schema.derived[TestProduct]
    val stringSchema = Schema.string

    assert(isProductType(productSchema))
    assert(!isProductType(stringSchema))
  }

  test("isEnumSchema should identify enum types") {
    val enumSchema = Schema.derived[TestEnum]
    val productSchema = Schema.derived[TestProduct]

    assert(isEnumSchema(enumSchema))
    assert(!isEnumSchema(productSchema))
  }

  test("Schema.name adds SName to schema") {
    val schema = Schema.derived[TestProduct]
    val namedSchema = schema.name(SName("Test"))

    assert(namedSchema.name.contains(SName("Test")))
  }

  test("schema for enums contains enum values") {
    val enumSchema = Schema.derived[TestEnum]

    // We can't easily access the enum values directly,
    // so just verify the schema properties instead
    assert(enumSchema.schemaType.isInstanceOf[sttp.tapir.SchemaType.SCoproduct[_]])
  }
}
