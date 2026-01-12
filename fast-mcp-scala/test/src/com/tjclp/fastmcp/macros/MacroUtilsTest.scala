package com.tjclp.fastmcp.macros

import io.circe.Json
import io.circe.parser.parse
import org.scalatest.funsuite.AnyFunSuite // For .asJson

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

  test(
    "injectParamDescriptions should not add description if property value is not a JSON object"
  ) {
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

  // --- injectParamMetadata Tests ---

  test("injectParamMetadata should return original JSON for empty metadata map") {
    val inputJsonString = """{
      "type": "object",
      "properties": { "name": { "type": "string" } },
      "required": ["name"]
    }"""
    val inputJson = unsafeParse(inputJsonString)
    val metadata = Map.empty[String, ParamMetadata]
    val expectedJson = inputJson // Expect no change

    val actualJson = MacroUtils.injectParamMetadata(inputJson, metadata)
    assert(actualJson == expectedJson)
  }

  test("injectParamMetadata should add description to properties") {
    val inputJsonString = """{
      "type": "object",
      "properties": {
        "name": { "type": "string" }
      },
      "required": ["name"]
    }"""
    val inputJson = unsafeParse(inputJsonString)
    val metadata = Map("name" -> ParamMetadata(description = Some("The user's name")))

    val actualJson = MacroUtils.injectParamMetadata(inputJson, metadata)
    val desc = actualJson.hcursor.downField("properties").downField("name").downField("description").as[String]
    assert(desc == Right("The user's name"))
  }

  test("injectParamMetadata should add examples array to properties") {
    val inputJsonString = """{
      "type": "object",
      "properties": {
        "name": { "type": "string" }
      },
      "required": ["name"]
    }"""
    val inputJson = unsafeParse(inputJsonString)
    val metadata = Map("name" -> ParamMetadata(examples = List("john_doe")))

    val actualJson = MacroUtils.injectParamMetadata(inputJson, metadata)
    val examples = actualJson.hcursor.downField("properties").downField("name").downField("examples").as[List[String]]
    assert(examples == Right(List("john_doe")))
  }

  test("injectParamMetadata should update required array when required=false") {
    val inputJsonString = """{
      "type": "object",
      "properties": {
        "name": { "type": "string" },
        "age": { "type": "integer" }
      },
      "required": ["name", "age"]
    }"""
    val inputJson = unsafeParse(inputJsonString)
    // Mark "age" as not required
    val metadata = Map("age" -> ParamMetadata(required = false))

    val actualJson = MacroUtils.injectParamMetadata(inputJson, metadata)
    val required = actualJson.hcursor.downField("required").as[List[String]]
    assert(required == Right(List("name")))
  }

  test("injectParamMetadata should add to required array when required=true") {
    val inputJsonString = """{
      "type": "object",
      "properties": {
        "name": { "type": "string" },
        "age": { "type": "integer" }
      },
      "required": ["name"]
    }"""
    val inputJson = unsafeParse(inputJsonString)
    // Mark "age" as required
    val metadata = Map("age" -> ParamMetadata(required = true))

    val actualJson = MacroUtils.injectParamMetadata(inputJson, metadata)
    val required = actualJson.hcursor.downField("required").as[List[String]].getOrElse(Nil).toSet
    assert(required == Set("name", "age"))
  }

  test("injectParamMetadata should replace property with custom schema") {
    val inputJsonString = """{
      "type": "object",
      "properties": {
        "status": { "type": "string" }
      },
      "required": ["status"]
    }"""
    val inputJson = unsafeParse(inputJsonString)
    val customSchema = """{"type": "string", "enum": ["active", "inactive", "pending"]}"""
    val metadata = Map("status" -> ParamMetadata(schema = Some(customSchema)))

    val actualJson = MacroUtils.injectParamMetadata(inputJson, metadata)
    val enumValues = actualJson.hcursor.downField("properties").downField("status").downField("enum").as[List[String]]
    assert(enumValues == Right(List("active", "inactive", "pending")))
  }

  test("injectParamMetadata should handle all fields together") {
    val inputJsonString = """{
      "type": "object",
      "properties": {
        "username": { "type": "string" },
        "age": { "type": "integer" }
      },
      "required": ["username", "age"]
    }"""
    val inputJson = unsafeParse(inputJsonString)
    val metadata = Map(
      "username" -> ParamMetadata(
        description = Some("The username"),
        examples = List("john_doe"),
        required = true
      ),
      "age" -> ParamMetadata(
        description = Some("User's age"),
        examples = List("25"),
        required = false
      )
    )

    val actualJson = MacroUtils.injectParamMetadata(inputJson, metadata)

    // Check username has description and examples
    val usernameDesc = actualJson.hcursor.downField("properties").downField("username").downField("description").as[String]
    val usernameExamples = actualJson.hcursor.downField("properties").downField("username").downField("examples").as[List[String]]
    assert(usernameDesc == Right("The username"))
    assert(usernameExamples == Right(List("john_doe")))

    // Check age has description and examples
    val ageDesc = actualJson.hcursor.downField("properties").downField("age").downField("description").as[String]
    val ageExamples = actualJson.hcursor.downField("properties").downField("age").downField("examples").as[List[String]]
    assert(ageDesc == Right("User's age"))
    assert(ageExamples == Right(List("25")))

    // Check required array only contains "username" (age was marked as not required)
    val required = actualJson.hcursor.downField("required").as[List[String]]
    assert(required == Right(List("username")))
  }

  test("injectParamMetadata should remove required array when all params are optional") {
    val inputJsonString = """{
      "type": "object",
      "properties": {
        "name": { "type": "string" }
      },
      "required": ["name"]
    }"""
    val inputJson = unsafeParse(inputJsonString)
    val metadata = Map("name" -> ParamMetadata(required = false))

    val actualJson = MacroUtils.injectParamMetadata(inputJson, metadata)
    val hasRequired = actualJson.hcursor.downField("required").succeeded
    assert(!hasRequired, "required array should be removed when empty")
  }

  test("injectParamMetadata should handle invalid custom schema gracefully") {
    val inputJsonString = """{
      "type": "object",
      "properties": {
        "status": { "type": "string" }
      },
      "required": ["status"]
    }"""
    val inputJson = unsafeParse(inputJsonString)
    // Invalid JSON in schema
    val metadata = Map("status" -> ParamMetadata(schema = Some("not valid json {")))

    val actualJson = MacroUtils.injectParamMetadata(inputJson, metadata)
    // Should fall back to original property definition
    val propType = actualJson.hcursor.downField("properties").downField("status").downField("type").as[String]
    assert(propType == Right("string"))
  }

}
