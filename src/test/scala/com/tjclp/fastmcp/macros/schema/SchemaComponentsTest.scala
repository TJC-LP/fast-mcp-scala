package com.tjclp.fastmcp.macros.schema

import com.tjclp.fastmcp.macros.Color
import com.tjclp.fastmcp.macros.JsonSchemaMacro
import io.circe.Json
import org.scalatest.funsuite.AnyFunSuite
import sttp.tapir.Schema
import sttp.tapir.SchemaType.*
import sttp.tapir.generic.auto.*

/** Tests for the schema package components that support JSON Schema generation for function
  * parameters. This includes FunctionAnalyzer, SchemaExtractor, and SchemaGenerator.
  */
class SchemaComponentsTest extends AnyFunSuite {

  // Simple case class for testing
  case class Person(name: String, age: Int, email: Option[String])
  case class Address(street: String, city: String, zipCode: String)
  case class User(name: String, address: Address)

  // Test SchemaGenerator's generateProductField functionality through the macro
  test("SchemaGenerator should create correct product fields for various types") {
    def testFunction(
        stringParam: String,
        intParam: Int,
        boolParam: Boolean,
        optParam: Option[Double],
        enumParam: Color,
        personParam: Person
    ): Unit = ()

    val schema = JsonSchemaMacro.schemaForFunctionArgs(testFunction)

    // Validate schema structure
    assert(schema.isObject)
    val properties = schema.hcursor.downField("properties").focus.getOrElse(Json.Null)
    assert(properties.isObject)

    // Check field types were correctly generated
    assert(
      properties.hcursor.downField("stringParam").downField("type").as[String].contains("string")
    )
    assert(
      properties.hcursor.downField("intParam").downField("type").as[String].contains("integer")
    )
    assert(
      properties.hcursor.downField("boolParam").downField("type").as[String].contains("boolean")
    )

    // Check option field
    val optField = properties.hcursor.downField("optParam")
    assert(!optField.failed, "Option field should be present")

    // In the current schema generation, optional fields aren't explicitly marked with "required": false
    // but are instead omitted from the "required" array in the parent object
    val requiredFields = schema.hcursor.downField("required").as[List[String]].getOrElse(List.empty)
    assert(!requiredFields.contains("optParam"), "Option field should not be required")

    // Check enum field
    val enumField = properties.hcursor.downField("enumParam")
    assert(enumField.downField("type").as[String].contains("string"))
    val enumValues = enumField.downField("enum").as[List[String]].getOrElse(List.empty)
    assert(enumValues.toSet == Set("RED", "GREEN", "BLUE", "YELLOW"))

    // Check object field
    val personField = properties.hcursor.downField("personParam")
    assert(personField.downField("type").as[String].contains("object"))
    assert(
      personField
        .downField("properties")
        .downField("name")
        .downField("type")
        .as[String]
        .contains("string")
    )
    assert(
      personField
        .downField("properties")
        .downField("age")
        .downField("type")
        .as[String]
        .contains("integer")
    )
  }

  // Test SchemaExtractor's enum detection and naming logic
  test("SchemaExtractor should detect enums and apply string-based representation") {
    def processColor(color: Color): String = color.toString
    val schema = JsonSchemaMacro.schemaForFunctionArgs(processColor)

    val colorField = schema.hcursor.downField("properties").downField("color")
    assert(colorField.downField("type").as[String].contains("string"))

    // Check enum values were extracted
    val enumValues = colorField.downField("enum").as[List[String]].getOrElse(List.empty)
    assert(enumValues.nonEmpty)
    assert(enumValues.contains("RED"))
    assert(enumValues.contains("GREEN"))
    assert(enumValues.contains("BLUE"))
    assert(enumValues.contains("YELLOW"))
  }

  // Test SchemaExtractor's naming logic for product types
  test("SchemaExtractor should assign appropriate names to product types") {
    case class TestProduct(value: String)

    def processProduct(product: TestProduct): Unit = ()
    val schema = JsonSchemaMacro.schemaForFunctionArgs(processProduct)

    val productField = schema.hcursor.downField("properties").downField("product")

    // In our inlined implementation, we should at least verify the type was correctly identified
    assert(productField.downField("type").as[String].contains("object"))
    assert(
      productField
        .downField("properties")
        .downField("value")
        .downField("type")
        .as[String]
        .contains("string")
    )
  }

  // Test FunctionAnalyzer's parameter extraction through the macro
  test("FunctionAnalyzer should extract parameter names and types correctly") {
    def complexFunction(name: String, age: Int, details: Map[String, String], user: User): Unit = ()

    val schema = JsonSchemaMacro.schemaForFunctionArgs(complexFunction)
    val properties = schema.hcursor.downField("properties").focus.getOrElse(Json.Null)

    // Verify all parameter names were extracted
    assert(properties.hcursor.downField("name").focus.isDefined)
    assert(properties.hcursor.downField("age").focus.isDefined)
    assert(properties.hcursor.downField("details").focus.isDefined)
    assert(properties.hcursor.downField("user").focus.isDefined)

    // Verify complex nested types
    val userField = properties.hcursor.downField("user")
    assert(userField.downField("properties").downField("name").focus.isDefined)
    assert(userField.downField("properties").downField("address").focus.isDefined)

    // Verify even deeper nesting
    val addressField = userField.downField("properties").downField("address")
    assert(addressField.downField("properties").downField("street").focus.isDefined)
    assert(addressField.downField("properties").downField("city").focus.isDefined)
    assert(addressField.downField("properties").downField("zipCode").focus.isDefined)
  }

  // Test parameter exclusion functionality
  test("FunctionAnalyzer should respect excluded parameter list") {
    def functionWithContext(name: String, age: Int, ctx: String): Unit = ()

    val schema = JsonSchemaMacro.schemaForFunctionArgs(functionWithContext, List("ctx"))
    val properties = schema.hcursor.downField("properties").focus.getOrElse(Json.Null)

    // Verify included parameters
    assert(properties.hcursor.downField("name").focus.isDefined)
    assert(properties.hcursor.downField("age").focus.isDefined)

    // Verify excluded parameter is not present
    assert(properties.hcursor.downField("ctx").focus.isEmpty)
  }

  // Test function references
  test("FunctionAnalyzer should extract parameters from function references") {
    // Define a method with named parameters
    def processUser(userName: String, userAge: Int): Unit = ()

    // Use method reference - the macro should extract the original parameter names
    val schema = JsonSchemaMacro.schemaForFunctionArgs(processUser)
    val properties = schema.hcursor.downField("properties").focus.getOrElse(Json.Null)

    // Verify parameter names were preserved
    assert(properties.hcursor.downField("userName").focus.isDefined)
    assert(properties.hcursor.downField("userAge").focus.isDefined)
  }
}
