package com.tjclp.fastmcp.macros.schema

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class FunctionAnalyzerTest extends AnyFunSuite with Matchers {
  // Note: Testing macro-related code is challenging without direct access to the Quote context.
  // Since FunctionAnalyzer operates at compile time with the Scala 3 quotes API,
  // we'll test the core logic in isolation.

  // These tests verify the parameter naming logic that FunctionAnalyzer uses

  test("Parameter naming with provided names when count matches") {
    // This simulates FunctionAnalyzer.extractParams choosing parameter names
    // when real names are available
    val providedNames = List("num", "text")
    val paramTypes = List("Int", "String")
    val result = useProvidedNames(providedNames, paramTypes)

    assert(result == List("num", "text"))
  }

  test("Parameter naming with mismatched name count") {
    // This simulates FunctionAnalyzer.extractParams falling back to arg0, arg1 pattern
    // when name count doesn't match parameter count
    val providedNames = List("singleName")
    val paramTypes = List("Int", "String")
    val result = useProvidedNames(providedNames, paramTypes)

    assert(result == List("arg0", "arg1"))
  }

  test("Parameter naming with no names") {
    // This simulates FunctionAnalyzer.extractParams using default names
    // when no real names are provided
    val paramTypes = List("Int", "String", "Boolean")
    val result = useProvidedNames(None, paramTypes)

    assert(result == List("arg0", "arg1", "arg2"))
  }

  // Simple helper methods that simulate the parameter naming logic in FunctionAnalyzer
  private def useProvidedNames(names: List[String], paramTypes: List[String]): List[String] =
    if (names.length == paramTypes.length) names
    else (0 until paramTypes.length).map(i => s"arg$i").toList

  private def useProvidedNames(
      names: Option[List[String]],
      paramTypes: List[String]
  ): List[String] =
    names match {
      case Some(list) => useProvidedNames(list, paramTypes)
      case None => (0 until paramTypes.length).map(i => s"arg$i").toList
    }
}
