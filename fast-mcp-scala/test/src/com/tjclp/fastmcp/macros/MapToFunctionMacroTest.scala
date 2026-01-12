package com.tjclp.fastmcp.macros

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Tests for the MapToFunctionMacro that converts a function to a Map[String, Any] => R function
  */
class MapToFunctionMacroTest extends AnyFunSuite with Matchers {

  // Test enum with lowercase values

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

  // Test with a function taking a case class
  test("should convert function with case class parameters") {
    def greet(person: Person): String = s"Hello, ${person.name}! You are ${person.age} years old."

    val mapFunction = MapToFunctionMacro.callByMap(greet)

    // Test with directly providing a Person object
    val person = Person("Alice", 30)
    val result1 = mapFunction.asInstanceOf[Map[String, Any] => String](Map("person" -> person))
    result1 should be("Hello, Alice! You are 30 years old.")

    // Test with providing a Map for the Person object - this is what we get from JSON
    val result2 = mapFunction(Map("person" -> Map("name" -> "Bob", "age" -> 25)))
    result2 should be("Hello, Bob! You are 25 years old.")
  }

  // Test with a function using optional parameters
  test("should handle optional parameters") {
    def optionalGreet(name: String, title: Option[String] = None): String =
      title.map(t => s"$t $name").getOrElse(name)

    val mapFunction = MapToFunctionMacro.callByMap(optionalGreet)

    // Test with only required parameter
    val result1 = mapFunction.asInstanceOf[Map[String, Any] => String](Map("name" -> "Alice"))
    result1 should be("Alice")

    // Test with explicitly providing the optional as None
    val result2 =
      mapFunction.asInstanceOf[Map[String, Any] => String](Map("name" -> "Alice", "title" -> None))
    result2 should be("Alice")

    // Test with optional parameter provided as Some
    val result3 = mapFunction.asInstanceOf[Map[String, Any] => String](
      Map("name" -> "Alice", "title" -> Some("Dr."))
    )
    result3 should be("Dr. Alice")

    // Test with optional parameter provided as direct value (string) - this is what we get from JSON
    val result4 =
      mapFunction.asInstanceOf[Map[String, Any] => String](Map("name" -> "Alice", "title" -> "Dr."))
    result4 should be("Dr. Alice")
  }

  // Test with uppercase enum parameters (traditional Java-style)
  test("should handle uppercase enum parameters") {
    def getColorHex(color: Color): String =
      color match
        case Color.RED => "#FF0000"
        case Color.GREEN => "#00FF00"
        case Color.BLUE => "#0000FF"
        case Color.YELLOW => "#FFFF00"

    val mapFunction = MapToFunctionMacro.callByMap(getColorHex)

    // Test with string value - should map to enum
    val result1 = mapFunction.asInstanceOf[Map[String, Any] => String](Map("color" -> "RED"))
    result1 should be("#FF0000")

    // Test with enum value directly
    val result2 = mapFunction.asInstanceOf[Map[String, Any] => String](Map("color" -> Color.GREEN))
    result2 should be("#00FF00")
  }

  // Test with lowercase enum parameters (Scala 3 style)
  test("should handle lowercase enum parameters") {
    def formatText(text: String, style: TextStyle): String =
      style match
        case TextStyle.plain => text
        case TextStyle.bold => s"**$text**"
        case TextStyle.italic => s"*$text*"
        case TextStyle.code => s"`$text`"
        case TextStyle.heading => s"# $text"

    val mapFunction = MapToFunctionMacro.callByMap(formatText)

    // Test with string value
    val result1 = mapFunction.asInstanceOf[Map[String, Any] => String](
      Map("text" -> "Hello", "style" -> "bold")
    )
    result1 should be("**Hello**")

    // Test with enum value directly
    val result2 = mapFunction.asInstanceOf[Map[String, Any] => String](
      Map("text" -> "World", "style" -> TextStyle.italic)
    )
    result2 should be("*World*")
  }

  // Test with a complex function with multiple enum parameters
  test("should handle multiple enum parameters") {
    case class FormattedOutput(originalText: String, formattedText: String)

    def formatText(
        text: String,
        style: TextStyle = TextStyle.plain,
        format: OutputFormat = OutputFormat.text
    ): FormattedOutput = {
      val styledText = (style, format) match
        case (TextStyle.plain, _) => text
        case (TextStyle.bold, OutputFormat.html) => s"<strong>$text</strong>"
        case (TextStyle.bold, _) => s"**$text**"
        case (TextStyle.italic, OutputFormat.html) => s"<em>$text</em>"
        case (TextStyle.italic, _) => s"*$text*"
        case (TextStyle.code, OutputFormat.html) => s"<code>$text</code>"
        case (TextStyle.code, _) => s"`$text`"
        case (TextStyle.heading, OutputFormat.html) => s"<h1>$text</h1>"
        case (TextStyle.heading, _) => s"# $text"

      FormattedOutput(text, styledText)
    }

    val mapFunction = MapToFunctionMacro.callByMap(formatText)

    // Test with string values for both enums
    val result1 = mapFunction.asInstanceOf[Map[String, Any] => FormattedOutput](
      Map("text" -> "Hello", "style" -> "bold", "format" -> "html")
    )

    result1.originalText should be("Hello")
    result1.formattedText should be("<strong>Hello</strong>")

    // Test with one enum direct and one as string
    val result2 = mapFunction.asInstanceOf[Map[String, Any] => FormattedOutput](
      Map("text" -> "World", "style" -> TextStyle.italic, "format" -> "markdown")
    )

    result2.originalText should be("World")
    result2.formattedText should be("*World*")
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
