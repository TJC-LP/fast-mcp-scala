package com.tjclp.fastmcp.macros.test

import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.scala.ClassTagExtensions
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.tjclp.fastmcp.macros.DeriveJacksonConverter
import com.tjclp.fastmcp.macros.JacksonConverter

import scala.reflect.ClassTag

// Example: Complex filter class similar to the one in the external app
case class Filter(
    column: String,
    op: String, // In real app this would be a union type
    value: String
)

object Filter:
  // Using automatic derivation
  given JacksonConverter[Filter] = DeriveJacksonConverter.derived[Filter]

// Alternative implementation showing custom converter
object FilterAlternative:

  given alternativeConverter: JacksonConverter[Filter] =
    JacksonConverter.fromPartialFunction[Filter] {
      case map: Map[String, Any] =>
        Filter(
          column = map("column").toString,
          op = map.getOrElse("op", "=").toString,
          value = map("value").toString
        )
      case jMap: java.util.Map[String, Any] =>
        import scala.jdk.CollectionConverters.*
        val map = jMap.asScala.toMap
        Filter(
          column = map("column").toString,
          op = map.getOrElse("op", "=").toString,
          value = map("value").toString
        )
    }

// Example: Using enhanced collection converters
case class QueryFilters(filters: Seq[Filter])

object QueryFilters:
  // Automatically gets Seq[Filter] converter from the given Filter converter
  given JacksonConverter[QueryFilters] = DeriveJacksonConverter.derived[QueryFilters]

// Example: Custom converter with transform
case class User(name: String, age: Int)

object User:

  // Convert strings in "name:age" format
  given JacksonConverter[User] =
    DeriveJacksonConverter.derived[User].contramap[String] { str =>
      str.split(":") match
        case Array(name, age) => Map("name" -> name, "age" -> age.toInt)
        case _ => str // Let default converter handle it
    }

// Example usage
object JacksonConverterExample:

  private val mapper = JsonMapper
    .builder()
    .addModule(DefaultScalaModule)
    .build() :: ClassTagExtensions

  def main(args: Array[String]): Unit =
    // Test Filter conversion
    val filterMap = Map("column" -> "status", "op" -> "=", "value" -> "active")
    val filter = summon[JacksonConverter[Filter]].convert("filter", filterMap, mapper)
    println(s"Converted filter: $filter")

    // Test Seq[Filter] conversion
    val filtersList = List(
      Map("column" -> "status", "op" -> "=", "value" -> "active"),
      Map("column" -> "count", "op" -> ">", "value" -> "10")
    )
    val filters = summon[JacksonConverter[Seq[Filter]]].convert("filters", filtersList, mapper)
    println(s"Converted filters: $filters")

    // Test QueryFilters
    val queryFiltersMap = Map("filters" -> filtersList)
    val queryFilters =
      summon[JacksonConverter[QueryFilters]].convert("queryFilters", queryFiltersMap, mapper)
    println(s"Converted queryFilters: $queryFilters")

    // Test User with transform
    val userString = "John:30"
    val user = summon[JacksonConverter[User]].convert("user", userString, mapper)
    println(s"Converted user from string: $user")

    val userMap = Map("name" -> "Jane", "age" -> 25)
    val user2 = summon[JacksonConverter[User]].convert("user", userMap, mapper)
    println(s"Converted user from map: $user2")
