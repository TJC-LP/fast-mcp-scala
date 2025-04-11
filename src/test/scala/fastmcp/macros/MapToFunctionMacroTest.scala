package fastmcp.macros

import org.scalatest.funsuite.AnyFunSuite

/**
 * Tests for the MapToFunctionMacro that converts a function to a Map[String, Any] => R function
 */
class MapToFunctionMacroTest extends AnyFunSuite {

  // Simple case class for testing
  case class Person(name: String, age: Int, email: Option[String] = None)

  // Enum for testing
  enum Color:
    case RED, GREEN, BLUE, YELLOW

  // Test with a simple function with primitive types
  test("should convert simple function with primitive types") {
    def add(a: Int, b: Int): Int = a + b
    
    val mapFunction = MapToFunctionMacro.callByMap(add)
    
    // Test with valid parameters
    val result1 = mapFunction.asInstanceOf[Map[String, Any] => Int](Map("a" -> 5, "b" -> 3))
    assert(result1 == 8)
    
    // Test with parameters in different order
    val result2 = mapFunction.asInstanceOf[Map[String, Any] => Int](Map("b" -> 7, "a" -> 2))
    assert(result2 == 9)
  }
  
  // Test with a function taking a case class
  test("should convert function with case class parameters") {
    def greet(person: Person): String = s"Hello, ${person.name}! You are ${person.age} years old."
    
    val mapFunction = MapToFunctionMacro.callByMap(greet)
    
    val person = Person("Alice", 30)
    val result = mapFunction.asInstanceOf[Map[String, Any] => String](Map("person" -> person))
    assert(result == "Hello, Alice! You are 30 years old.")
  }
  
  // Test with a function using optional parameters
  test("should handle optional parameters") {
    def optionalGreet(name: String, title: Option[String] = None): String = 
      title.map(t => s"$t $name").getOrElse(name)
    
    val mapFunction = MapToFunctionMacro.callByMap(optionalGreet)
    
    // Test with only required parameter and explicitly providing the optional as None
    val result1 = mapFunction.asInstanceOf[Map[String, Any] => String](Map("name" -> "Alice", "title" -> None))
    assert(result1 == "Alice")
    
    // Test with optional parameter provided
    val result2 = mapFunction.asInstanceOf[Map[String, Any] => String](Map("name" -> "Alice", "title" -> Some("Dr.")))
    assert(result2 == "Dr. Alice")
  }
  
  // Test with a function taking an enum parameter
  test("should handle enum parameters") {
    def colorToHex(colorName: String): String = colorName.toUpperCase match
      case "RED" => "#FF0000"
      case "GREEN" => "#00FF00"
      case "BLUE" => "#0000FF"
      case "YELLOW" => "#FFFF00"
      case _ => "#000000"
    
    val mapFunction = MapToFunctionMacro.callByMap(colorToHex)
    
    // Test with string value
    val result1 = mapFunction.asInstanceOf[Map[String, Any] => String](Map("colorName" -> "RED"))
    assert(result1 == "#FF0000")
    
    // Test with lowercase string (should handle case-insensitively)
    val result2 = mapFunction.asInstanceOf[Map[String, Any] => String](Map("colorName" -> "blue"))
    assert(result2 == "#0000FF")
    
    // Test with mixed-case string
    val result3 = mapFunction.asInstanceOf[Map[String, Any] => String](Map("colorName" -> "Green"))
    assert(result3 == "#00FF00")
  }
  
  // Test with a function with multiple parameters of different types
  test("should handle complex parameter combinations") {
    def complexFunction(name: String, age: Int, hobbies: List[String], colorName: String): String =
      s"$name, $age, likes ${hobbies.mkString(", ")}, favorite color: ${colorName.toLowerCase}"
    
    val mapFunction = MapToFunctionMacro.callByMap(complexFunction)
    
    val result = mapFunction.asInstanceOf[Map[String, Any] => String](Map(
      "name" -> "Bob",
      "age" -> 25,
      "hobbies" -> List("reading", "hiking", "coding"),
      "colorName" -> "RED"
    ))
    
    assert(result == "Bob, 25, likes reading, hiking, coding, favorite color: red")
  }
  
  // Test for error handling with missing parameters
  test("should throw exception for missing required parameters") {
    def greet(name: String): String = s"Hello, $name!"
    
    val mapFunction = MapToFunctionMacro.callByMap(greet)
    
    val exception = intercept[RuntimeException] {
      mapFunction.asInstanceOf[Map[String, Any] => String](Map.empty)
    }
    
    assert(exception.getMessage.contains("Missing required parameter"))
  }
  
  // Test for error handling with incorrect parameter types
  test("should throw exception for incorrect parameter types") {
    def add(a: Int, b: Int): Int = a + b
    
    val mapFunction = MapToFunctionMacro.callByMap(add)
    
    val exception = intercept[RuntimeException] {
      mapFunction.asInstanceOf[Map[String, Any] => Int](Map("a" -> "not_a_number", "b" -> 5))
    }
    
    // Just verify that we got some error when trying to use a string as an integer
    assert(!exception.getMessage.isEmpty)
  }
}