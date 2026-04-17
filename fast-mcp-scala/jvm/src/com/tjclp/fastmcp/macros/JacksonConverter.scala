package com.tjclp.fastmcp.macros

import scala.deriving.Mirror
import scala.jdk.CollectionConverters.*
import scala.reflect.ClassTag
import scala.util.NotGiven

import com.tjclp.fastmcp.core.McpDecodeContext
import com.tjclp.fastmcp.core.McpDecoder
import com.tjclp.fastmcp.server.McpContext

/** Typeclass that converts a raw `Any` value (from a Map) to `T`.
  *
  * Low-level custom implementations receive a shared [[JacksonConversionContext]] backed by Jackson
  * 3.
  */
trait JacksonConverter[T] extends McpDecoder[T]:
  def convert(name: String, rawValue: Any, context: JacksonConversionContext): T

  final override def decode(name: String, rawValue: Any, context: McpDecodeContext): T =
    context match
      case jackson: JacksonConversionContext =>
        convert(name, rawValue, jackson)
      case _ =>
        throw new IllegalArgumentException(
          s"Unsupported decode context ${context.getClass.getName} for JacksonConverter"
        )

  /** Create a new converter by transforming the input before conversion. */
  override def contramap[U](f: U => Any): JacksonConverter[T] =
    val self = this
    new JacksonConverter[T]:
      def convert(name: String, rawValue: Any, context: JacksonConversionContext): T =
        val transformed =
          try f(rawValue.asInstanceOf[U])
          catch case _: ClassCastException => rawValue
        self.convert(name, transformed, context)

/** Low-priority fallback for non-product values Jackson 3 can handle directly. */
@SuppressWarnings(Array("org.wartremover.warts.Null"))
trait JacksonConverterLowPriority:

  protected def failNull(name: String, tpe: String): Nothing =
    throw new RuntimeException(s"Null value provided for parameter '$name' of type $tpe")

  protected def doConvert[T: ClassTag](
      name: String,
      rawValue: Any,
      context: JacksonConversionContext
  ): T =
    context.convertValue[T](name, rawValue)

  private def enumValues[T: ClassTag]: Option[IndexedSeq[T]] =
    val runtimeClass = summon[ClassTag[T]].runtimeClass
    try
      val valuesMethod = runtimeClass.getMethod("values")
      val rawValues = valuesMethod.invoke(null).asInstanceOf[Array[Any]]
      Some(rawValues.iterator.map(_.asInstanceOf[T]).toIndexedSeq)
    catch case _: ReflectiveOperationException => None

  protected def doConvertEnum[T: ClassTag](
      name: String,
      rawValue: Any,
      context: JacksonConversionContext
  ): T =
    enumValues[T] match
      case Some(values) =>
        rawValue match
          case s: String =>
            values
              .find(_.toString == s)
              .getOrElse(
                throw new RuntimeException(
                  s"Cannot parse '$s' as ${summon[ClassTag[T]].runtimeClass.getSimpleName} for parameter '$name'"
                )
              )
          case n: Number =>
            values
              .lift(n.intValue())
              .getOrElse(
                throw new RuntimeException(
                  s"Cannot parse ordinal '${n.intValue()}' as ${summon[ClassTag[T]].runtimeClass.getSimpleName} for parameter '$name'"
                )
              )
          case _ =>
            doConvert[T](name, rawValue, context)
      case None =>
        doConvert[T](name, rawValue, context)

  given [T: ClassTag](using NotGiven[Mirror.ProductOf[T]]): JacksonConverter[T] with

    def convert(name: String, rawValue: Any, context: JacksonConversionContext): T =
      if rawValue == null then failNull(name, summon[ClassTag[T]].runtimeClass.getSimpleName)

      val runtimeClass = summon[ClassTag[T]].runtimeClass
      if runtimeClass.isInstance(rawValue) then rawValue.asInstanceOf[T]
      else if runtimeClass.isEnum || enumValues[T].isDefined then
        doConvertEnum[T](name, rawValue, context)
      else doConvert[T](name, rawValue, context)

@SuppressWarnings(Array("org.wartremover.warts.Null"))
object JacksonConverter extends JacksonConverterLowPriority:

  inline given [T](using Mirror.ProductOf[T], ClassTag[T]): JacksonConverter[T] =
    DeriveJacksonConverter.derived[T]

  // Basic instances
  given JacksonConverter[String] with

    def convert(name: String, rawValue: Any, context: JacksonConversionContext): String =
      if rawValue == null then failNull(name, "String")
      rawValue match
        case s: String => s
        case _ => doConvert[String](name, rawValue, context)

  given JacksonConverter[Int] with

    def convert(name: String, rawValue: Any, context: JacksonConversionContext): Int =
      if rawValue == null then failNull(name, "Int")
      doConvert[Int](name, rawValue, context)

  given JacksonConverter[Unit] with

    def convert(name: String, rawValue: Any, context: JacksonConversionContext): Unit =
      rawValue match
        case null | None => ()
        case m: Map[?, ?] if m.isEmpty => ()
        case jMap: java.util.Map[?, ?] if jMap.isEmpty => ()
        case _ =>
          throw new RuntimeException(
            s"Cannot convert non-empty value for parameter '$name' to Unit. Value: $rawValue"
          )

  // Option instance treats null or missing as None
  @scala.annotation.nowarn("msg=unused implicit parameter")
  given [A: ClassTag](using JacksonConverter[A]): JacksonConverter[Option[A]] with

    def convert(name: String, rawValue: Any, context: JacksonConversionContext): Option[A] =
      rawValue match
        case null | None => None
        case Some(v) => Some(summon[JacksonConverter[A]].convert(name, v, context))
        case v => Some(summon[JacksonConverter[A]].convert(name, v, context))

  // Identity converter for McpContext as per Context Propagation Upgrade
  given JacksonConverter[McpContext] with

    def convert(
        key: String,
        raw: Any,
        context: JacksonConversionContext
    ): McpContext =
      raw.asInstanceOf[McpContext]

  // Enhanced List/Seq converter that handles various input formats
  @scala.annotation.nowarn("msg=unused implicit parameter")
  given [A: ClassTag](using conv: JacksonConverter[A]): JacksonConverter[List[A]] with

    def convert(name: String, rawValue: Any, context: JacksonConversionContext): List[A] =
      if rawValue == null then failNull(name, "List")

      val elements = rawValue match
        case str: String =>
          try context.parseJsonArray(name, str)
          catch case _: Exception => List(str) // Single string element
        case jList: java.util.List[?] => jList.asScala.toList
        case arr: Array[?] => arr.toList
        case seq: Seq[?] => seq.toList
        case single => List(single)

      elements.zipWithIndex.map { case (elem, idx) =>
        conv.convert(s"$name[$idx]", elem, context)
      }

  given [A: ClassTag](using conv: JacksonConverter[A]): JacksonConverter[Seq[A]] with

    def convert(name: String, rawValue: Any, context: JacksonConversionContext): Seq[A] =
      summon[JacksonConverter[List[A]]].convert(name, rawValue, context).toSeq

  // Enhanced Map converter
  given [K: ClassTag, V: ClassTag](using
      kConv: JacksonConverter[K],
      vConv: JacksonConverter[V]
  ): JacksonConverter[Map[K, V]] with

    def convert(name: String, rawValue: Any, context: JacksonConversionContext): Map[K, V] =
      if rawValue == null then failNull(name, "Map")

      rawValue match
        case jMap: java.util.Map[?, ?] =>
          jMap.asScala.toMap.map { case (k, v) =>
            val key = kConv.convert(s"$name.key", k, context)
            val value = vConv.convert(s"$name[$k]", v, context)
            key -> value
          }
        case sMap: Map[?, ?] =>
          sMap.map { case (k, v) =>
            val key = kConv.convert(s"$name.key", k, context)
            val value = vConv.convert(s"$name[$k]", v, context)
            key -> value
          }
        case str: String =>
          val parsed = context.parseJsonObject(name, str)
          summon[JacksonConverter[Map[K, V]]].convert(name, parsed, context)
        case _ =>
          throw new RuntimeException(
            s"Cannot convert $rawValue to Map[${summon[ClassTag[K]].runtimeClass.getSimpleName}, ${summon[ClassTag[V]].runtimeClass.getSimpleName}]"
          )

  // Boolean converter with flexible parsing
  given JacksonConverter[Boolean] with

    def convert(name: String, rawValue: Any, context: JacksonConversionContext): Boolean =
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
        case _ => doConvert[Boolean](name, rawValue, context)

  given JacksonConverter[Long] with

    def convert(name: String, rawValue: Any, context: JacksonConversionContext): Long =
      if rawValue == null then failNull(name, "Long")
      doConvert[Long](name, rawValue, context)

  given JacksonConverter[Double] with

    def convert(name: String, rawValue: Any, context: JacksonConversionContext): Double =
      if rawValue == null then failNull(name, "Double")
      doConvert[Double](name, rawValue, context)

  given JacksonConverter[Float] with

    def convert(name: String, rawValue: Any, context: JacksonConversionContext): Float =
      if rawValue == null then failNull(name, "Float")
      doConvert[Float](name, rawValue, context)

  def fromPartialFunction[T: ClassTag](pf: PartialFunction[Any, T]): JacksonConverter[T] =
    new JacksonConverter[T]:
      def convert(name: String, rawValue: Any, context: JacksonConversionContext): T =
        if rawValue == null then failNull(name, summon[ClassTag[T]].runtimeClass.getSimpleName)
        pf.lift(rawValue) match
          case Some(value) => value
          case None =>
            doConvert[T](name, rawValue, context)
