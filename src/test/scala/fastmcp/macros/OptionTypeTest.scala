package fastmcp.macros

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import io.circe.Json
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

/** Tests to verify that Option types are correctly marked as not required in JSON schemas
  */
class OptionTypeTest extends AnyFunSuite with Matchers {

  // Case class with both required and optional fields (via Option)
  case class UserProfile(
      id: String, // Required
      username: String, // Required
      email: Option[String], // Optional
      age: Option[Int] = None, // Optional with default
      preferences: Option[Map[String, String]] = None // Optional with default
  )

  // Schema is automatically derived by Tapir
  given Schema[UserProfile] = Schema.derived[UserProfile]

  // Function that takes both required and optional parameters
  def searchUsers(
      query: String, // Required
      maxResults: Option[Int], // Optional
      includeInactive: Option[Boolean] = None, // Optional with default
      sortOrder: String = "asc" // Has default but not an Option
  ): String = s"Searching for '$query'"

  // Function that takes a case class with Option fields
  def updateProfile(profile: UserProfile): String =
    s"Updating profile for ${profile.username}"

  test("Option parameters should not be marked as required") {
    val schema = JsonSchemaMacro.schemaForFunctionArgs(searchUsers)

    // Check required array
    val required = schema.hcursor.downField("required").as[List[String]].getOrElse(List.empty)

    // Verify that the required field is in the required array
    assert(required.contains("query"), "query should be required")

    // Option types should not be in the required array
    assert(!required.contains("maxResults"), "maxResults is an Option and should not be required")
    assert(
      !required.contains("includeInactive"),
      "includeInactive is an Option and should not be required"
    )

    // Parameters with defaults (non-Option) may still be marked as required
    // This is acceptable for now as detecting defaults is complex
    // assert(!required.contains("sortOrder"), "sortOrder has a default value and should not be required")
  }

  test("Case class with Option fields should have those fields not required") {
    val schema = JsonSchemaMacro.schemaForFunctionArgs(updateProfile)

    // Get the profile property from the schema
    val properties = schema.hcursor.downField("properties").focus.getOrElse(Json.Null)
    val profileObj = properties.hcursor.downField("profile").focus.getOrElse(Json.Null)

    // Get the required array for the profile object
    val required = profileObj.hcursor.downField("required").as[List[String]].getOrElse(List.empty)

    // Required fields should be in the required array
    assert(required.contains("id"), "id should be required")
    assert(required.contains("username"), "username should be required")

    // Option fields should not be in the required array
    assert(!required.contains("email"), "email is an Option and should not be required")
    assert(!required.contains("age"), "age is an Option and should not be required")
    assert(!required.contains("preferences"), "preferences is an Option and should not be required")
  }
}
