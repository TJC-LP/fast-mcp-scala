package fastmcp.macros

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class MapToFunctionMacroSimpleTest extends AnyFunSuite with Matchers {

  // Test with a simple function with primitive types
  test("should convert simple function with primitive types") {
    def add(a: Int, b: Int): Int = a + b

    val mapFunction = MapToFunctionMacro.callByMap(add)

    // Test with valid parameters
    val result1 = mapFunction.asInstanceOf[Map[String, Any] => Int](Map("a" -> 5, "b" -> 3))
    result1 should be(8)

    // Test with parameters in different order
    val result2 = mapFunction.asInstanceOf[Map[String, Any] => Int](Map("b" -> 7, "a" -> 2))
    result2 should be(9)
  }

  // Test for error handling with missing parameters
  test("should throw exception for missing required parameters") {
    def greet(name: String): String = s"Hello, $name!"

    val mapFunction = MapToFunctionMacro.callByMap(greet)

    val exception = intercept[RuntimeException] {
      mapFunction.asInstanceOf[Map[String, Any] => String](Map.empty)
    }

    exception.getMessage should include("Key not found in map")
  }

  // Test for error handling with incorrect parameter types
  test("should throw exception for incorrect parameter types") {
    def add(a: Int, b: Int): Int = a + b

    val mapFunction = MapToFunctionMacro.callByMap(add)

    val exception = intercept[RuntimeException] {
      mapFunction.asInstanceOf[Map[String, Any] => Int](Map("a" -> "not_a_number", "b" -> 5))
    }

    // Just verify that we got some error when trying to use a string as an integer
    exception.getMessage should not be empty
  }
}
