package com.tjclp.fastmcp.macros

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import com.tjclp.fastmcp.core.ToolParam // Needed for Schema derivation

/** Tests that the description provided in the @Param annotation is correctly included in the
  * generated JSON schema by JsonSchemaMacro.
  */
class ParamAnnotationSchemaTest extends AnyFunSuite with Matchers {

  // Define a simple case class for testing
  case class TestData(id: Int, value: String)

  // Define the test function with @Param annotations including descriptions
  def testFunctionWithParamDesc(
      @ToolParam("The unique identifier for the data") dataId: Int,
      @ToolParam("The actual test data object") testData: TestData,
      @ToolParam("Flag indicating if processing is active") isActive: Boolean
  ): Unit = ()

  test("@Param description should be included in the generated JSON schema") {
    // Generate the schema using the macro
    // TODO
//    val schemaJson: Json = JsonSchemaMacro.schemaForFunctionArgs(testFunctionWithParamDesc)
//
//    // Get the properties object from the schema
//    val properties = schemaJson.hcursor.downField("properties").focus.getOrElse(Json.Null)
//    properties.isObject shouldBe true
//
//    // --- Verify description for 'dataId' (primitive) ---
//    val dataIdProp = properties.hcursor.downField("dataId").focus.getOrElse(Json.Null)
//    dataIdProp.isObject shouldBe true
//    val dataIdDesc = dataIdProp.hcursor.downField("description").as[String]
//    dataIdDesc.isRight shouldBe true
//    dataIdDesc.getOrElse("") shouldBe "The unique identifier for the data"
//
//    // --- Verify description for 'testData' (case class) ---
//    val testDataProp = properties.hcursor.downField("testData").focus.getOrElse(Json.Null)
//    testDataProp.isObject shouldBe true
//    val testDataDesc = testDataProp.hcursor.downField("description").as[String]
//    testDataDesc.isRight shouldBe true
//    testDataDesc.getOrElse("") shouldBe "The actual test data object"
//    // Optionally, check that the nested schema for TestData itself doesn't have this description
//    val nestedTestDataDesc = testDataProp.hcursor.downField("properties").downField("id").downField("description").as[String]
//    nestedTestDataDesc.isLeft shouldBe true // Assuming TestData fields don't have descriptions
//
//    // --- Verify description for 'isActive' (primitive) ---
//    val isActiveProp = properties.hcursor.downField("isActive").focus.getOrElse(Json.Null)
//    isActiveProp.isObject shouldBe true
//    val isActiveDesc = isActiveProp.hcursor.downField("description").as[String]
//    isActiveDesc.isRight shouldBe true
//    isActiveDesc.getOrElse("") shouldBe "Flag indicating if processing is active"
  }
}
