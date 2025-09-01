package com.tjclp.fastmcp.macros

import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.scala.ClassTagExtensions
import com.tjclp.fastmcp.server.McpContext

import scala.jdk.CollectionConverters.*
import scala.reflect.ClassTag

/** Typeclass that converts a raw `Any` value (from a Map) to `T` using Jackson. */
trait JacksonConverter[T]:
  def convert(name: String, rawValue: Any, mapper: JsonMapper & ClassTagExtensions): T

  /** Optional custom module to register with Jackson for this converter */
  def customModule: Option[SimpleModule] = None

  /** Create a new converter by transforming the input before conversion */
  def contramap[U](f: U => Any): JacksonConverter[T] =
    val self = this
    new JacksonConverter[T]:
      def convert(name: String, rawValue: Any, mapper: JsonMapper & ClassTagExtensions): T =
        val transformed =
          try f(rawValue.asInstanceOf[U])
          catch case _: ClassCastException => rawValue
        self.convert(name, transformed, mapper)
      override def customModule = self.customModule

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

  // Enhanced List/Seq converter that handles various input formats
  given [A: ClassTag](using conv: JacksonConverter[A]): JacksonConverter[List[A]] with

    def convert(name: String, rawValue: Any, mapper: JsonMapper & ClassTagExtensions): List[A] =
      if rawValue == null then failNull(name, "List")

      val elements = rawValue match
        case str: String =>
          // Try to parse as JSON array
          try
            val parsed = mapper.readValue(str, classOf[java.util.List[Any]])
            parsed.asScala.toList
          catch case _: Exception => List(str) // Single string element
        case jList: java.util.List[?] => jList.asScala.toList
        case arr: Array[?] => arr.toList
        case seq: Seq[?] => seq.toList
        case single => List(single)

      elements.zipWithIndex.map { case (elem, idx) =>
        conv.convert(s"$name[$idx]", elem, mapper)
      }

  given [A: ClassTag](using conv: JacksonConverter[A]): JacksonConverter[Seq[A]] with

    def convert(name: String, rawValue: Any, mapper: JsonMapper & ClassTagExtensions): Seq[A] =
      summon[JacksonConverter[List[A]]].convert(name, rawValue, mapper).toSeq

  // Enhanced Map converter
  given [K: ClassTag, V: ClassTag](using
      kConv: JacksonConverter[K],
      vConv: JacksonConverter[V]
  ): JacksonConverter[Map[K, V]] with

    def convert(name: String, rawValue: Any, mapper: JsonMapper & ClassTagExtensions): Map[K, V] =
      if rawValue == null then failNull(name, "Map")

      rawValue match
        case jMap: java.util.Map[?, ?] =>
          jMap.asScala.toMap.map { case (k, v) =>
            val key = kConv.convert(s"$name.key", k, mapper)
            val value = vConv.convert(s"$name[$k]", v, mapper)
            key -> value
          }
        case sMap: Map[?, ?] =>
          sMap.map { case (k, v) =>
            val key = kConv.convert(s"$name.key", k, mapper)
            val value = vConv.convert(s"$name[$k]", v, mapper)
            key -> value
          }
        case str: String =>
          // Try to parse as JSON object
          val parsed = mapper.readValue(str, classOf[java.util.Map[Any, Any]])
          summon[JacksonConverter[Map[K, V]]].convert(name, parsed, mapper)
        case _ =>
          throw new RuntimeException(
            s"Cannot convert $rawValue to Map[${summon[ClassTag[K]].runtimeClass.getSimpleName}, ${summon[ClassTag[V]].runtimeClass.getSimpleName}]"
          )

  // Boolean converter with flexible parsing
  given JacksonConverter[Boolean] with

    def convert(name: String, rawValue: Any, mapper: JsonMapper & ClassTagExtensions): Boolean =
      if rawValue == null then failNull(name, "Boolean")
      rawValue match
        case b: Boolean => b
        case s: String =>
          s.toLowerCase match
            case "true" | "yes" | "1" | "on" => true
            case "false" | "no" | "0" | "off" => false
            case _ =>
              throw new RuntimeException(s"Cannot parse '$s' as Boolean for parameter '$name'")
        case n: Number => n.intValue() != 0
        case _ => doConvert[Boolean](name, rawValue, "Boolean", mapper)

  // Long converter
  given JacksonConverter[Long] with

    def convert(name: String, rawValue: Any, mapper: JsonMapper & ClassTagExtensions): Long =
      if rawValue == null then failNull(name, "Long")
      doConvert[Long](name, rawValue, "Long", mapper)

  // Double converter
  given JacksonConverter[Double] with

    def convert(name: String, rawValue: Any, mapper: JsonMapper & ClassTagExtensions): Double =
      if rawValue == null then failNull(name, "Double")
      doConvert[Double](name, rawValue, "Double", mapper)

  // Float converter
  given JacksonConverter[Float] with

    def convert(name: String, rawValue: Any, mapper: JsonMapper & ClassTagExtensions): Float =
      if rawValue == null then failNull(name, "Float")
      doConvert[Float](name, rawValue, "Float", mapper)

  // Helper methods for creating custom converters
  def fromPartialFunction[T: ClassTag](pf: PartialFunction[Any, T]): JacksonConverter[T] =
    new JacksonConverter[T]:

      def convert(name: String, rawValue: Any, mapper: JsonMapper & ClassTagExtensions): T =
        if rawValue == null then failNull(name, summon[ClassTag[T]].runtimeClass.getSimpleName)
        pf.lift(rawValue) match
          case Some(value) => value
          case None =>
            doConvert[T](name, rawValue, summon[ClassTag[T]].runtimeClass.getSimpleName, mapper)

  def withCustomModule[T: ClassTag](
      module: SimpleModule
  )(using base: JacksonConverter[T]): JacksonConverter[T] =
    new JacksonConverter[T]:
      override def customModule = Some(module)

      def convert(name: String, rawValue: Any, mapper: JsonMapper & ClassTagExtensions): T =
        val enhancedMapper = mapper.rebuild().addModule(module).build() :: ClassTagExtensions
        base.convert(name, rawValue, enhancedMapper)

  // Fallback for any other type T with a ClassTag: let Jackson handle it directly
  given [T: ClassTag]: JacksonConverter[T] with

    def convert(name: String, rawValue: Any, mapper: JsonMapper & ClassTagExtensions): T =
      if rawValue == null then failNull(name, summon[ClassTag[T]].runtimeClass.getSimpleName)
      doConvert[T](name, rawValue, summon[ClassTag[T]].runtimeClass.getSimpleName, mapper)
