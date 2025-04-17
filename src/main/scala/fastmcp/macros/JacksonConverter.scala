package fastmcp.macros

import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.scala.ClassTagExtensions
import scala.reflect.ClassTag

/** Typeclass that converts a raw `Any` value (from a Map) to `T` using Jackson. */
trait JacksonConverter[T]:
  def convert(name: String, rawValue: Any, mapper: JsonMapper & ClassTagExtensions): T

object JacksonConverter:

  // Uniform null/missing handling
  private def failNull(name: String, tpe: String) =
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

  given JacksonConverter[Long] with

    def convert(name: String, rawValue: Any, mapper: JsonMapper & ClassTagExtensions): Long =
      if rawValue == null then failNull(name, "Long")
      doConvert[Long](name, rawValue, "Long", mapper)

  given JacksonConverter[Double] with

    def convert(name: String, rawValue: Any, mapper: JsonMapper & ClassTagExtensions): Double =
      if rawValue == null then failNull(name, "Double")
      doConvert[Double](name, rawValue, "Double", mapper)

  given JacksonConverter[Boolean] with

    def convert(name: String, rawValue: Any, mapper: JsonMapper & ClassTagExtensions): Boolean =
      if rawValue == null then failNull(name, "Boolean")
      doConvert[Boolean](name, rawValue, "Boolean", mapper)

  // Collection instances
  given [A: ClassTag](using JacksonConverter[A]): JacksonConverter[List[A]] with

    def convert(name: String, rawValue: Any, mapper: JsonMapper & ClassTagExtensions): List[A] =
      if rawValue == null then failNull(name, "List[A]")
      doConvert[List[A]](name, rawValue, "List[A]", mapper)

  given [A: ClassTag](using JacksonConverter[A]): JacksonConverter[Vector[A]] with

    def convert(name: String, rawValue: Any, mapper: JsonMapper & ClassTagExtensions): Vector[A] =
      if rawValue == null then failNull(name, "Vector[A]")
      doConvert[Vector[A]](name, rawValue, "Vector[A]", mapper)

  given [A: ClassTag](using JacksonConverter[A]): JacksonConverter[Set[A]] with

    def convert(name: String, rawValue: Any, mapper: JsonMapper & ClassTagExtensions): Set[A] =
      if rawValue == null then failNull(name, "Set[A]")
      doConvert[Set[A]](name, rawValue, "Set[A]", mapper)

  // Map with String keys
  given [V: ClassTag](using JacksonConverter[V]): JacksonConverter[Map[String, V]] with

    def convert(
        name: String,
        rawValue: Any,
        mapper: JsonMapper & ClassTagExtensions
    ): Map[String, V] =
      if rawValue == null then failNull(name, "Map[String, V]")
      doConvert[Map[String, V]](name, rawValue, "Map[String, V]", mapper)

  // Option instance treats null or missing as None
  given [A: ClassTag](using JacksonConverter[A]): JacksonConverter[Option[A]] with

    def convert(name: String, rawValue: Any, mapper: JsonMapper & ClassTagExtensions): Option[A] =
      rawValue match
        case null | None => None
        case Some(v) => Some(summon[JacksonConverter[A]].convert(name, v, mapper))
        case v => Some(summon[JacksonConverter[A]].convert(name, v, mapper))

  // Identity converter for McpContext as per Context Propagation Upgrade
  given JacksonConverter[fastmcp.server.McpContext] with

    def convert(
        key: String,
        raw: Any,
        mapper: JsonMapper & ClassTagExtensions
    ): fastmcp.server.McpContext =
      raw.asInstanceOf[fastmcp.server.McpContext]

  // Fallback for any other type T with a ClassTag: let Jackson handle it directly
  given [T: ClassTag]: JacksonConverter[T] with

    def convert(name: String, rawValue: Any, mapper: JsonMapper & ClassTagExtensions): T =
      if rawValue == null then failNull(name, summon[ClassTag[T]].runtimeClass.getSimpleName)
      doConvert[T](name, rawValue, summon[ClassTag[T]].runtimeClass.getSimpleName, mapper)
