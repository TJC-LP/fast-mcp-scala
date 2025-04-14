package fastmcp.macros

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import io.circe.Json
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

/**
 * Tests to verify that fields with Option types (even with default values) are correctly marked as not required in schemas
 */
class DefaultValueTest extends AnyFunSuite with Matchers {

  // Case class with both required and optional fields (via Option with Some defaults)
  case class UserConfig(
    username: String,              // Required - no default
    email: String,                 // Required - no default
    age: Option[Int] = Some(18),   // Optional via Option with default value
    isActive: Option[Boolean] = Some(true), // Optional via Option with default value
    preferences: Option[Map[String, String]] = Some(Map.empty)  // Optional via Option with default value
  )

  // Schema is automatically derived by Tapir
  given Schema[UserConfig] = Schema.derived[UserConfig]

  // Function that takes the case class as a parameter
  def processUserConfig(config: UserConfig): String = s"Processed config for ${config.username}"

  test("fields with Option types (even with defaults) should not be marked as required") {
    val schema = JsonSchemaMacro.schemaForFunctionArgs(processUserConfig)
    
    // Verify the schema structure
    val properties = schema.hcursor.downField("properties").focus.getOrElse(Json.Null)
    assert(properties.isObject)
    
    // The config property should contain the entire UserConfig schema
    val configObj = properties.hcursor.downField("config").focus.getOrElse(Json.Null)
    assert(configObj.isObject)
    
    // Check that configObj has a properties field containing all UserConfig fields
    val configProperties = configObj.hcursor.downField("properties").focus.getOrElse(Json.Null)
    assert(configProperties.isObject)
    
    // Verify fields are present in the property object
    assert(configProperties.hcursor.downField("username").focus.isDefined, "username field should be present")
    assert(configProperties.hcursor.downField("email").focus.isDefined, "email field should be present")
    assert(configProperties.hcursor.downField("age").focus.isDefined, "age field should be present")
    assert(configProperties.hcursor.downField("isActive").focus.isDefined, "isActive field should be present")
    assert(configProperties.hcursor.downField("preferences").focus.isDefined, "preferences field should be present")
    
    // Verify that the configObj has a required array
    val required = configObj.hcursor.downField("required").as[List[String]].getOrElse(List.empty)

    // Required fields should be in the required array
    assert(required.contains("username"), "username should be required")
    assert(required.contains("email"), "email should be required")

    // Option fields should NOT be in the required array
    assert(!required.contains("age"), "age is an Option and should not be required")
    assert(!required.contains("isActive"), "isActive is an Option and should not be required")
    assert(!required.contains("preferences"), "preferences is an Option and should not be required")
  }
  
  test("functions with mixed required/optional parameters should mark only required ones") {
    // Function with mixed parameter types
    def mixedParamsFunction(
      requiredParam: String,                     // Required
      optionalParam: Option[String] = Some("default") // Optional via Option with default
    ): String = s"$requiredParam - ${optionalParam.getOrElse("fallback")}"
    
    val schema = JsonSchemaMacro.schemaForFunctionArgs(mixedParamsFunction)
    
    // Get properties section
    val properties = schema.hcursor.downField("properties").focus.getOrElse(Json.Null)
    assert(properties.isObject)
    
    // Verify both parameters are present in the schema
    assert(properties.hcursor.downField("requiredParam").focus.isDefined, "requiredParam should be present in properties")
    assert(properties.hcursor.downField("optionalParam").focus.isDefined, "optionalParam should be present in properties")
    
    // Check required array at the top level
    val required = schema.hcursor.downField("required").as[List[String]].getOrElse(List.empty)

    // requiredParam should be in the required array
    assert(required.contains("requiredParam"), "requiredParam should be required")

    // optionalParam is an Option and shouldn't be required
    assert(!required.contains("optionalParam"), "optionalParam is an Option and should not be required")
  }
}