package fastmcp.macros

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import io.circe.Json
import io.circe.parser.parse
import sttp.tapir.*
import sttp.tapir.Schema.annotations.{description, encodedExample}
import sttp.tapir.generic.auto.*

/**
 * Tests to ensure that tapir annotations are properly included in the JSON schema
 */
class ParamAnnotationTest extends AnyFunSuite with Matchers {

  def configFunction(
                      options: ConfigOptions
                    ): String = s"${options.name}: ${options.value}"

  // Test with a case class parameter
  case class ConfigOptions(
                            @description("Name of the configuration") name: String,
                            @description("Value of the configuration") value: Int
                          )

  object ConfigOptions {
    // Provide a Schema instance for ConfigOptions
    given Schema[ConfigOptions] = Schema.derived[ConfigOptions]
  }

  test("should handle schema annotations on case class properties") {
    val schema = JsonSchemaMacro.schemaForFunctionArgs(configFunction)

    // Get properties section
    val properties = schema.hcursor.downField("properties").focus.getOrElse(Json.Null)

    // Verify options has the description
    val options = properties.hcursor.downField("options").focus.getOrElse(Json.Null)

    // Make sure the ConfigOptions's nested properties have descriptions too
    val optionsProperties = options.hcursor.downField("properties").focus.getOrElse(Json.Null)
    assert(optionsProperties.isObject)

    // Check name property
    val nameProperty = optionsProperties.hcursor.downField("name").focus.getOrElse(Json.Null)
    val nameDescription = nameProperty.hcursor.downField("description").as[String].getOrElse("")
    assert(nameDescription == "Name of the configuration")

    // Check value property
    val valueProperty = optionsProperties.hcursor.downField("value").focus.getOrElse(Json.Null)
    val valueDescription = valueProperty.hcursor.downField("description").as[String].getOrElse("")
    assert(valueDescription == "Value of the configuration")
  }
}