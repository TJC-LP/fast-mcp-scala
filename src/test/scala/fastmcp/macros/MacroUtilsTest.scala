package fastmcp.macros

import org.scalatest.funsuite.AnyFunSuite // Using ScalaTest AnyFunSuite
import io.circe.{Json, JsonObject}
import io.circe.parser.parse
import io.circe.syntax.* // For .asJson

class MacroUtilsTest extends AnyFunSuite { // Ensure this matches the import

  // Helper to parse JSON, failing the test on error for conciseness
  private def unsafeParse(jsonString: String): Json = {
    parse(jsonString) match {
      case Right(json) => json
      case Left(err) => fail(s"Failed to parse JSON: $err\nJSON:\n$jsonString")
    }
  }

  // --- resolveJsonRefs Tests ---

  test("resolveJsonRefs should return original JSON when no refs or defs are present") {
    val inputJsonString = """{ "type": "object", "properties": { "name": { "type": "string" } } }"""
    val inputJson = unsafeParse(inputJsonString)
    val expectedJson = inputJson // Expect no change

    val actualJson = MacroUtils.resolveJsonRefs(inputJson)
    assert(actualJson == expectedJson) // Use ScalaTest's assert
  }

  test("resolveJsonRefs should resolve a simple top-level ref") {
    val inputJsonString = """{
      "$defs": { "Address": { "type": "string", "description": "An address" } },
      "type": "object",
      "properties": {
        "homeAddress": { "$ref": "#/$defs/Address" }
      }
    }"""
    val inputJson = unsafeParse(inputJsonString)
    val expectedJsonString = """{
      "type": "object",
      "properties": {
        "homeAddress": { "type": "string", "description": "An address" }
      }
    }"""
    val expectedJson = unsafeParse(expectedJsonString)

    val actualJson = MacroUtils.resolveJsonRefs(inputJson)
    assert(actualJson == expectedJson) // Use ScalaTest's assert
  }

  test("resolveJsonRefs should resolve nested refs") {
    val inputJsonString = """{
      "$defs": {
        "Street": { "type": "string", "description": "Street name" },
        "Address": { "type": "object", "properties": { "street": { "$ref": "#/$defs/Street" } } }
      },
      "type": "object",
      "properties": {
        "location": { "$ref": "#/$defs/Address" }
      }
    }"""
    val inputJson = unsafeParse(inputJsonString)
    val expectedJsonString = """{
      "type": "object",
      "properties": {
        "location": { "type": "object", "properties": { "street": { "type": "string", "description": "Street name" } } }
      }
    }"""
    val expectedJson = unsafeParse(expectedJsonString)

    val actualJson = MacroUtils.resolveJsonRefs(inputJson)
    assert(actualJson == expectedJson) // Use ScalaTest's assert
  }

  test("resolveJsonRefs should resolve refs inside an array's items") {
    val inputJsonString = """{
      "$defs": { "Tag": { "type": "string", "enum": ["A", "B"] } },
      "type": "object",
      "properties": {
        "tags": { "type": "array", "items": { "$ref": "#/$defs/Tag" } }
      }
    }"""
    val inputJson = unsafeParse(inputJsonString)
    val expectedJsonString = """{
      "type": "object",
      "properties": {
        "tags": { "type": "array", "items": { "type": "string", "enum": ["A", "B"] } }
      }
    }"""
    val expectedJson = unsafeParse(expectedJsonString)

    val actualJson = MacroUtils.resolveJsonRefs(inputJson)
    assert(actualJson == expectedJson) // Use ScalaTest's assert
  }

  test("resolveJsonRefs should leave unresolvable refs untouched but remove defs") {
    val inputJsonString = """{
      "$defs": { "Something": { "type": "integer" } },
      "type": "object",
      "properties": {
        "missing": { "$ref": "#/$defs/DoesNotExist" }
      }
    }"""
    val inputJson = unsafeParse(inputJsonString)
    // Expect the $defs to be removed, but the ref to remain
    val expectedJsonString = """{
      "type": "object",
      "properties": {
        "missing": { "$ref": "#/$defs/DoesNotExist" }
      }
    }"""
    val expectedJson = unsafeParse(expectedJsonString)

    val actualJson = MacroUtils.resolveJsonRefs(inputJson)
    assert(actualJson == expectedJson) // Use ScalaTest's assert
  }

  // --- injectParamDescriptions Tests ---

  test("injectParamDescriptions should add descriptions to properties") {
    val inputJsonString = """{
      "type": "object",
      "properties": {
        "name": { "type": "string" },
        "age": { "type": "integer" }
      }
    }"""
    val inputJson = unsafeParse(inputJsonString)
    val descriptions = Map("name" -> "The user's name", "age" -> "The user's age")
    val expectedJsonString = """{
      "type": "object",
      "properties": {
        "name": { "type": "string", "description": "The user's name" },
        "age": { "type": "integer", "description": "The user's age" }
      }
    }"""
    val expectedJson = unsafeParse(expectedJsonString)

    val actualJson = MacroUtils.injectParamDescriptions(inputJson, descriptions)
    assert(actualJson == expectedJson) // Use ScalaTest's assert
  }

  test("injectParamDescriptions should return original JSON if 'properties' field is missing") {
    val inputJsonString = """{ "type": "string" }"""
    val inputJson = unsafeParse(inputJsonString)
    val descriptions = Map("someField" -> "Some description")
    val expectedJson = inputJson // Expect no change

    val actualJson = MacroUtils.injectParamDescriptions(inputJson, descriptions)
    assert(actualJson == expectedJson) // Use ScalaTest's assert
  }

  test("injectParamDescriptions should return original JSON for empty 'properties' object") {
    val inputJsonString = """{ "type": "object", "properties": {} }"""
    val inputJson = unsafeParse(inputJsonString)
    val descriptions = Map("someField" -> "Some description")
    val expectedJson = inputJson // Expect no change

    val actualJson = MacroUtils.injectParamDescriptions(inputJson, descriptions)
    assert(actualJson == expectedJson) // Use ScalaTest's assert
  }

  test("injectParamDescriptions should return original JSON for empty description map") {
    val inputJsonString = """{
      "type": "object",
      "properties": { "name": { "type": "string" } }
    }"""
    val inputJson = unsafeParse(inputJsonString)
    val descriptions = Map.empty[String, String]
    val expectedJson = inputJson // Expect no change

    val actualJson = MacroUtils.injectParamDescriptions(inputJson, descriptions)
    assert(actualJson == expectedJson) // Use ScalaTest's assert
  }

  test("injectParamDescriptions should only add descriptions for keys present in the map") {
    val inputJsonString = """{
      "type": "object",
      "properties": {
        "name": { "type": "string" },
        "city": { "type": "string" }
      }
    }"""
    val inputJson = unsafeParse(inputJsonString)
    val descriptions = Map("name" -> "The user's name") // Only description for 'name'
    val expectedJsonString = """{
      "type": "object",
      "properties": {
        "name": { "type": "string", "description": "The user's name" },
        "city": { "type": "string" }
      }
    }"""
    val expectedJson = unsafeParse(expectedJsonString)

    val actualJson = MacroUtils.injectParamDescriptions(inputJson, descriptions)
    assert(actualJson == expectedJson) // Use ScalaTest's assert
  }

  test("injectParamDescriptions should overwrite existing description fields") {
    val inputJsonString = """{
      "type": "object",
      "properties": {
        "name": { "type": "string", "description": "Old description" }
      }
    }"""
    val inputJson = unsafeParse(inputJsonString)
    val descriptions = Map("name" -> "New description")
    val expectedJsonString = """{
      "type": "object",
      "properties": {
        "name": { "type": "string", "description": "New description" }
      }
    }"""
    val expectedJson = unsafeParse(expectedJsonString)

    val actualJson = MacroUtils.injectParamDescriptions(inputJson, descriptions)
    assert(actualJson == expectedJson) // Use ScalaTest's assert
  }

  test("injectParamDescriptions should not add description if property value is not a JSON object") {
    // Note: This structure is not typical for JSON Schema properties,
    // but tests the robustness of the `.asObject` check.
    val inputJsonString = """{
        "type": "object",
        "properties": {
          "weird_prop": "just a string value"
        }
      }"""
    val inputJson = unsafeParse(inputJsonString)
    val descriptions = Map("weird_prop" -> "A description for a weird property")
    val expectedJson = inputJson // Expect no change as the value isn't an object

    val actualJson = MacroUtils.injectParamDescriptions(inputJson, descriptions)
    assert(actualJson == expectedJson) // Use ScalaTest's assert
  }

}
