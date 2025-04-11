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
  object Person {
    // Make sure Schema[Person] is available
    given Schema[Person] = Schema.derived
  }

  // Nested case class for testing schema references
  case class Address(street: String, city: String, zipCode: String)
  object Address {
    given Schema[Address] = Schema.derived
  }

  case class User(person: Person, address: Address, tags: List[String])
  object User {
    given Schema[User] = Schema.derived
  }
  
  // Enum for testing
  enum Color:
    case RED, GREEN, BLUE, YELLOW
  
  object Color:
    given Schema[Color] = Schema.derivedEnumeration.defaultStringBased

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

    // Check that we have a $defs section for the Person schema
    val defs = schema.hcursor.downField("$defs").focus.getOrElse(Json.Null)
    assert(defs.isObject)
    assert(defs.hcursor.downField("Person").focus.isDefined)
    
    // Verify person property references the Person schema
    val properties = schema.hcursor.downField("properties").focus.getOrElse(Json.Null)
    val personRef = properties.hcursor.downField("person").downField("$ref").as[String].getOrElse("")
    assert(personRef == "#/$defs/Person")
    
    // Verify tags property is an array
    val tagsProperty = properties.hcursor.downField("tags").focus.getOrElse(Json.Null)
    assert(tagsProperty.hcursor.downField("type").as[String].getOrElse("") == "array")
  }
  
  // Test with nested case classes
  test("should generate schema for function with nested case classes") {
    def userFunction(user: User): Unit = ()
    
    val schema = JsonSchemaMacro.schemaForFunctionArgs(userFunction)
    
    // Verify we have definitions for User, Person, and Address
    val defs = schema.hcursor.downField("$defs").focus.getOrElse(Json.Null)
    assert(defs.hcursor.downField("User").focus.isDefined)
    assert(defs.hcursor.downField("Person").focus.isDefined)
    assert(defs.hcursor.downField("Address").focus.isDefined)
    
    // Verify the user property references the User schema
    val properties = schema.hcursor.downField("properties").focus.getOrElse(Json.Null)
    val userRef = properties.hcursor.downField("user").downField("$ref").as[String].getOrElse("")
    assert(userRef == "#/$defs/User")
    
    // Navigate into the User schema and verify it references Person and Address schemas
    val userSchema = defs.hcursor.downField("User").focus.getOrElse(Json.Null)
    val userProperties = userSchema.hcursor.downField("properties").focus.getOrElse(Json.Null)
    
    // Check Person reference
    val personRef = userProperties.hcursor.downField("person").downField("$ref").as[String].getOrElse("")
    assert(personRef == "#/$defs/Person")
    
    // Check Address reference
    val addressRef = userProperties.hcursor.downField("address").downField("$ref").as[String].getOrElse("")
    assert(addressRef == "#/$defs/Address")
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
    
    // Check that we have a $defs section for the Color enum schema
    val defs = schema.hcursor.downField("$defs").focus.getOrElse(Json.Null)
    assert(defs.isObject)
    assert(defs.hcursor.downField("Color").focus.isDefined)
    
    // Verify color property references the Color schema
    val colorRef = properties.hcursor.downField("color").downField("$ref").as[String].getOrElse("")
    assert(colorRef == "#/$defs/Color")
    
    // Verify the Color enum definition has the correct structure
    val colorSchema = defs.hcursor.downField("Color").focus.getOrElse(Json.Null)
    assert(colorSchema.hcursor.downField("type").as[String].getOrElse("") == "string")
    
    // Verify the enum values are present
    val enumValues = colorSchema.hcursor.downField("enum").as[List[String]].getOrElse(List())
    assert(enumValues.nonEmpty)
    assert(enumValues.contains("RED"))
    assert(enumValues.contains("GREEN"))
    assert(enumValues.contains("BLUE"))
    assert(enumValues.contains("YELLOW"))
  }
}