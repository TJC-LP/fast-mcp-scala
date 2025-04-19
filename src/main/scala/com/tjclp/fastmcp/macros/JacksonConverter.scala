package com.tjclp.fastmcp.macros

import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.scala.ClassTagExtensions
import com.tjclp.fastmcp.server.McpContext

import scala.reflect.ClassTag

/** Typeclass that converts a raw `Any` value (from a Map) to `T` using Jackson. */
trait JacksonConverter[T]:
  def convert(name: String, rawValue: Any, mapper: JsonMapper & ClassTagExtensions): T

object JacksonConverter:

  // Uniform null/missing handling
  private def failNull(name: String, tpe: String): Unit =
    throw new RuntimeException(s"Null value provided for parameter '$name' of type $tpe")

  // DRY potent conversion with error wrapping
  private def doConvert[T: ClassTag](
      name: String,
      rawValue: Any,
      tpe: String,
      mapper: JsonMapper & ClassTagExtensions
  ): T =
    try mapper.convertValue[T](rawValue)
    catch
      case e: Exception =>
        throw new RuntimeException(
          s"Failed to convert value for parameter '$name' to type $tpe. Value: $rawValue",
          e
        )

  // Basic instances
  given JacksonConverter[String] with

    def convert(name: String, rawValue: Any, mapper: JsonMapper & ClassTagExtensions): String =
      if rawValue == null then failNull(name, "String")
      doConvert[String](name, rawValue, "String", mapper)

  given JacksonConverter[Int] with

    def convert(name: String, rawValue: Any, mapper: JsonMapper & ClassTagExtensions): Int =
      if rawValue == null then failNull(name, "Int")
      doConvert[Int](name, rawValue, "Int", mapper)

  // Option instance treats null or missing as None
  given [A: ClassTag](using JacksonConverter[A]): JacksonConverter[Option[A]] with

    def convert(name: String, rawValue: Any, mapper: JsonMapper & ClassTagExtensions): Option[A] =
      rawValue match
        case null | None => None
        case Some(v) => Some(summon[JacksonConverter[A]].convert(name, v, mapper))
        case v => Some(summon[JacksonConverter[A]].convert(name, v, mapper))

  // Identity converter for McpContext as per Context Propagation Upgrade
  given JacksonConverter[McpContext] with

    def convert(
        key: String,
        raw: Any,
        mapper: JsonMapper & ClassTagExtensions
    ): McpContext =
      raw.asInstanceOf[McpContext]

  // Fallback for any other type T with a ClassTag: let Jackson handle it directly
  given [T: ClassTag]: JacksonConverter[T] with

    def convert(name: String, rawValue: Any, mapper: JsonMapper & ClassTagExtensions): T =
      if rawValue == null then failNull(name, summon[ClassTag[T]].runtimeClass.getSimpleName)
      doConvert[T](name, rawValue, summon[ClassTag[T]].runtimeClass.getSimpleName, mapper)
