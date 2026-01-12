package com.tjclp.fastmcp.runtime

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Regression tests for RefResolver to ensure it correctly handles functions with various numbers
  * of arguments. Prior to the fix in commit 61d3392, functions with more than 3 arguments would
  * fail with a "symbolic reference class is not accessible" error.
  *
  * Note: Standard Scala Function types support up to 22 arguments (Function0 to Function22). Scala
  * 3 adds support for arbitrary function arity using Tuples via extension methods, but
  * RefResolver's direct pattern matching is limited to 22 arguments.
  */
class RefResolverTest extends AnyFunSuite with Matchers {

  // Test function with exactly 4 arguments
  test("invokeFunctionWithArgs should correctly invoke a function with 4 arguments") {
    def fourArgsFunction(a: Int, b: String, c: Double, d: Boolean): String =
      s"$a - $b - $c - $d"

    val result = RefResolver.invokeFunctionWithArgs(
      fourArgsFunction,
      List(42, "test", 3.14, true)
    )

    result should be("42 - test - 3.14 - true")
  }

  // Testing with a lambda/anonymous function with 4 args
  test("invokeFunctionWithArgs should correctly invoke a lambda function with 4 arguments") {
    val fourArgsLambda = (a: Int, b: String, c: Double, d: Boolean) => s"Lambda: $a - $b - $c - $d"

    val result = RefResolver.invokeFunctionWithArgs(
      fourArgsLambda,
      List(42, "test", 3.14, true)
    )

    result should be("Lambda: 42 - test - 3.14 - true")
  }

  // Test function with 5 arguments
  test("invokeFunctionWithArgs should correctly invoke a function with 5 arguments") {
    def fiveArgsFunction(a: Int, b: String, c: Double, d: Boolean, e: List[String]): String =
      s"$a - $b - $c - $d - ${e.mkString(",")}"

    val result = RefResolver.invokeFunctionWithArgs(
      fiveArgsFunction,
      List(42, "test", 3.14, true, List("x", "y", "z"))
    )

    result should be("42 - test - 3.14 - true - x,y,z")
  }

  // Test function with 10 arguments - testing a higher number in the middle range
  test("invokeFunctionWithArgs should correctly invoke a function with 10 arguments") {
    def tenArgsFunction(
        a1: Int,
        a2: Int,
        a3: Int,
        a4: Int,
        a5: Int,
        a6: Int,
        a7: Int,
        a8: Int,
        a9: Int,
        a10: Int
    ): Int =
      a1 + a2 + a3 + a4 + a5 + a6 + a7 + a8 + a9 + a10

    val result = RefResolver.invokeFunctionWithArgs(
      tenArgsFunction,
      List(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
    )

    result should be(55) // Sum of numbers 1 to 10
  }

  // Test function with maximum supported arguments (22)
  test("invokeFunctionWithArgs should correctly invoke a function with 22 arguments") {
    // Function that takes 22 arguments and joins them as strings
    def maxArgsFunction(
        a1: String,
        a2: String,
        a3: String,
        a4: String,
        a5: String,
        a6: String,
        a7: String,
        a8: String,
        a9: String,
        a10: String,
        a11: String,
        a12: String,
        a13: String,
        a14: String,
        a15: String,
        a16: String,
        a17: String,
        a18: String,
        a19: String,
        a20: String,
        a21: String,
        a22: String
    ): String = {
      Seq(
        a1,
        a2,
        a3,
        a4,
        a5,
        a6,
        a7,
        a8,
        a9,
        a10,
        a11,
        a12,
        a13,
        a14,
        a15,
        a16,
        a17,
        a18,
        a19,
        a20,
        a21,
        a22
      ).mkString("-")
    }

    val args = (1 to 22).map(_.toString).toList

    val result = RefResolver.invokeFunctionWithArgs(
      maxArgsFunction,
      args
    )

    result should be((1 to 22).map(_.toString).mkString("-"))
  }

  // Test handling of symbolic references with 4+ arguments - regression test for the specific error mentioned
  test("invokeFunctionWithArgs should correctly handle symbolic references with 4+ arguments") {
    // This test targets the specific error:
    // "symbolic reference class is not accessible: class com.tjclp.chat.mcp.VectorSearchMcpServer$$$Lambda$626/0x000000f8013844b0, from class com.tjclp.fastmcp.runtime.RefResolver$ (unnamed module @1b6d2f55)"

    // Create a class that contains a lambda with 4+ args to simulate a similar environment to VectorSearchMcpServer
    class TestContainer {
      // Lambda that will be created as an inner class (similar to the error case)
      val fourArgSymbolicFunction = (a: Int, b: String, c: Double, d: List[Any]) => {
        s"Symbolic-4Args: $a, $b, $c, ${d.mkString("[", ",", "]")}"
      }

      // Lambda with 5 args
      val fiveArgSymbolicFunction =
        (a: Int, b: String, c: Double, d: List[Any], e: Map[String, Any]) => {
          s"Symbolic-5Args: $a, $b, $c, ${d.mkString("[", ",", "]")}, ${e.mkString("{", ",", "}")}"
        }
    }

    val container = new TestContainer()

    // Test with 4 args
    val result4 = RefResolver.invokeFunctionWithArgs(
      container.fourArgSymbolicFunction,
      List(42, "test", 3.14, List("a", "b", "c"))
    )

    result4 should be("Symbolic-4Args: 42, test, 3.14, [a,b,c]")

    // Test with 5 args
    val result5 = RefResolver.invokeFunctionWithArgs(
      container.fiveArgSymbolicFunction,
      List(42, "test", 3.14, List("a", "b", "c"), Map("key" -> "value"))
    )

    result5 should be("Symbolic-5Args: 42, test, 3.14, [a,b,c], {key -> value}")
  }

  // Test lambda with type Any - similar to how symbolic references might be created in real code
  test(
    "invokeFunctionWithArgs should correctly handle lambda with Any types with 4+ arguments"
  ) {
    // Lambda with Any types - this creates a similar situation to compiled symbolic references
    val anyTypeLambda = (a: Any, b: Any, c: Any, d: Any) => {
      s"AnyTypes: $a - $b - $c - $d"
    }

    val result = RefResolver.invokeFunctionWithArgs(
      anyTypeLambda,
      List("val1", "val2", "val3", "val4")
    )

    result should be("AnyTypes: val1 - val2 - val3 - val4")
  }

  // Test behavior with more than 22 arguments
  test(
    "invokeFunctionWithArgs should throw an exception for functions with more than 22 arguments"
  ) {
    // We cannot define a function that takes 23+ parameters directly in Scala
    // So instead we'll use the generic fallback case in RefResolver by passing a function
    // object that doesn't match the standard FunctionN classes

    // Create a test class that can process a list of arbitrary length
    class ArbitraryArityFunction {
      def apply(args: Any*): String = args.mkString(", ")
    }

    val testFn = new ArbitraryArityFunction()
    val args = (1 to 25).toList // More than the 22 arguments directly supported

    // This should throw an exception for exceeding the argument limit
    val exception = intercept[IllegalArgumentException] {
      RefResolver.invokeFunctionWithArgs(testFn, args)
    }

    // Verify we get our custom error message
    exception.getMessage should include("maximum of 22 arguments")
  }
}
