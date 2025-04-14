package fastmcp.macros

import org.scalatest.funsuite.AnyFunSuite
import io.circe.Json
import io.circe.parser.parse
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

/**
 * Tests for the JsonSchemaMacro that generates JSON schema for function parameters
 */
class JsonSchemaMacroTest extends AnyFunSuite {

  // Simple case class for testing
  case class Person(name: String, age: Int, email: Option[String])

  // Nested case class for testing schema references
  case class Address(street: String, city: String, zipCode: String)

  case class User(person: Person, address: Address, tags: List[String])

  // Test with a simple function with primitive types
  test("should generate schema for simple function with primitive types") {
    def simpleFunction(name: String, age: Int, active: Boolean): Unit = ()
    
    val schema = JsonSchemaMacro.schemaForFunctionArgs(simpleFunction)
    
    // Verify basic structure
    assert(schema.isObject)
    assert(schema.hcursor.downField("type").as[String].getOrElse("") == "object")
    
    // Verify properties
    val properties = schema.hcursor.downField("properties").focus.getOrElse(Json.Null)
    assert(properties.isObject)
    
    // Check name property
    val nameProperty = properties.hcursor.downField("name").focus.getOrElse(Json.Null)
    assert(nameProperty.hcursor.downField("type").as[String].getOrElse("") == "string")
    
    // Check age property
    val ageProperty = properties.hcursor.downField("age").focus.getOrElse(Json.Null)
    assert(ageProperty.hcursor.downField("type").as[String].getOrElse("") == "integer")
    
    // Check active property
    val activeProperty = properties.hcursor.downField("active").focus.getOrElse(Json.Null)
    assert(activeProperty.hcursor.downField("type").as[String].getOrElse("") == "boolean")
  }
  
  // Test with a function that takes case classes
  test("should generate schema for function with case classes") {
    def complexFunction(person: Person, tags: List[String]): Unit = ()
    
    val schema = JsonSchemaMacro.schemaForFunctionArgs(complexFunction)

    // With our new implementation we no longer use $defs, but instead inline schemas in properties
    val properties = schema.hcursor.downField("properties").focus.getOrElse(Json.Null)
    
    // Verify person property contains an inlined schema 
    val personProperty = properties.hcursor.downField("person").focus.getOrElse(Json.Null)
    assert(personProperty.isObject)
    assert(personProperty.hcursor.downField("type").as[String].getOrElse("") == "object")
    
    // Check that person has its own properties section
    val personProps = personProperty.hcursor.downField("properties").focus.getOrElse(Json.Null)
    assert(personProps.isObject)
    assert(personProps.hcursor.downField("name").focus.isDefined)
    assert(personProps.hcursor.downField("age").focus.isDefined)
    
    // Verify tags property is an array
    val tagsProperty = properties.hcursor.downField("tags").focus.getOrElse(Json.Null)
    assert(tagsProperty.hcursor.downField("type").as[String].getOrElse("") == "array")
  }
  
  // Test with nested case classes
  test("should generate schema for function with nested case classes") {
    def userFunction(user: User): Unit = ()
    
    val schema = JsonSchemaMacro.schemaForFunctionArgs(userFunction)
    
    // With our new implementation we no longer use $defs, but instead inline all schemas in properties
    val properties = schema.hcursor.downField("properties").focus.getOrElse(Json.Null)
    
    // Verify the user property contains a nested schema
    val userProperty = properties.hcursor.downField("user").focus.getOrElse(Json.Null)
    assert(userProperty.isObject)
    assert(userProperty.hcursor.downField("type").as[String].getOrElse("") == "object")
    
    // Navigate into the User schema and verify it has nested Person and Address schemas
    val userProperties = userProperty.hcursor.downField("properties").focus.getOrElse(Json.Null)
    assert(userProperties.isObject)
    
    // Check Person is inlined 
    val personProperty = userProperties.hcursor.downField("person").focus.getOrElse(Json.Null)
    assert(personProperty.isObject)
    assert(personProperty.hcursor.downField("type").as[String].getOrElse("") == "object")
    
    // Check Address is inlined
    val addressProperty = userProperties.hcursor.downField("address").focus.getOrElse(Json.Null)
    assert(addressProperty.isObject)
    assert(addressProperty.hcursor.downField("type").as[String].getOrElse("") == "object")
  }
  
  // Test with optional parameters
  test("should generate schema for function with optional parameters") {
    def optionalParamsFunction(name: String, age: Option[Int], tags: Option[List[String]]): Unit = ()
    
    val schema = JsonSchemaMacro.schemaForFunctionArgs(optionalParamsFunction)
    val properties = schema.hcursor.downField("properties").focus.getOrElse(Json.Null)
    
    // Verify Optional parameters exist
    val ageProperty = properties.hcursor.downField("age").focus.getOrElse(Json.Null)
    assert(!ageProperty.isNull, "Age property should be present")
    
    val tagsProperty = properties.hcursor.downField("tags").focus.getOrElse(Json.Null)
    assert(!tagsProperty.isNull, "Tags property should be present")
    
    // Check the schema's overall structure 
    assert(schema.hcursor.downField("type").as[String].getOrElse("") == "object")
  }

  // Test with an enum parameter
  test("should generate schema for function with enum parameter") {
    def processColor(color: Color): String = color.toString
    
    val schema = JsonSchemaMacro.schemaForFunctionArgs(processColor)
    
    // Verify basic structure
    assert(schema.isObject)
    val properties = schema.hcursor.downField("properties").focus.getOrElse(Json.Null)
    assert(properties.isObject)
    
    // With our new implementation, enum values are inlined in the property
    val colorProperty = properties.hcursor.downField("color").focus.getOrElse(Json.Null)
    assert(colorProperty.isObject)
    assert(colorProperty.hcursor.downField("type").as[String].getOrElse("") == "string")
    
    // Verify the enum values are present directly in the property
    val enumValues = colorProperty.hcursor.downField("enum").as[List[String]].getOrElse(List())
    assert(enumValues.nonEmpty)
    assert(enumValues.contains("RED"))
    assert(enumValues.contains("GREEN"))
    assert(enumValues.contains("BLUE"))
    assert(enumValues.contains("YELLOW"))
  }
}