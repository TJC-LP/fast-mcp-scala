package com.tjclp.fastmcp.macros

import scala.quoted.*
import scala.deriving.Mirror
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.scala.ClassTagExtensions
import scala.reflect.ClassTag

/** Macro to automatically derive JacksonConverter instances for case classes */
object DeriveJacksonConverter:

  /** Derives a JacksonConverter for a case class T */
  inline def derived[T](using Mirror.Of[T], ClassTag[T]): JacksonConverter[T] =
    ${ derivedImpl[T] }

  private def derivedImpl[T: Type](using Quotes): Expr[JacksonConverter[T]] =
    import quotes.reflect.*

    val tpe = TypeRepr.of[T]
    val typeSymbol = tpe.typeSymbol

    // Check if it's a case class
    if !typeSymbol.isClassDef || !typeSymbol.flags.is(Flags.Case) then
      report.errorAndAbort(
        s"Can only derive JacksonConverter for case classes, but ${typeSymbol.name} is not a case class"
      )

    // Get the mirror
    val mirror = Expr.summon[Mirror.Of[T]].get

    mirror match
      case '{ $m: Mirror.ProductOf[T] } =>
        derivedProduct[T](m)
      case '{ $m: Mirror.SumOf[T] } =>
        derivedSum[T](m)
      case _ =>
        report.errorAndAbort(s"Cannot derive JacksonConverter for ${typeSymbol.name}")

  private def derivedProduct[T: Type](
      mirror: Expr[Mirror.ProductOf[T]]
  )(using Quotes): Expr[JacksonConverter[T]] =
    import quotes.reflect.*

    val tpe = TypeRepr.of[T]
    val typeName = Expr(tpe.typeSymbol.name)
    val ct = Expr
      .summon[ClassTag[T]]
      .getOrElse(
        report.errorAndAbort(s"No ClassTag available for ${tpe.typeSymbol.name}")
      )

    '{
      given ClassTag[T] = $ct
      new JacksonConverter[T]:
        def convert(name: String, rawValue: Any, mapper: JsonMapper & ClassTagExtensions): T =
          if rawValue == null then
            throw new RuntimeException(
              s"Null value provided for parameter '$name' of type ${$typeName}"
            )

          // Try different input formats
          rawValue match
            // If it's already the correct type, return it
            case t: T => t

            // If it's a Map, convert field by field
            case map: Map[String, Any] =>
              convertFromMap(name, map, mapper)

            case jMap: java.util.Map[String, Any] =>
              import scala.jdk.CollectionConverters.*
              convertFromMap(name, jMap.asScala.toMap, mapper)

            // Otherwise use Jackson's default conversion
            case _ =>
              try mapper.convertValue[T](rawValue)
              catch
                case e: Exception =>
                  throw new RuntimeException(
                    s"Failed to convert value for parameter '$name' to type ${$typeName}. Value: $rawValue",
                    e
                  )

        private def convertFromMap(
            paramName: String,
            map: Map[String, Any],
            mapper: JsonMapper & ClassTagExtensions
        ): T =
          // Let Jackson handle the conversion directly
          try mapper.convertValue[T](map)
          catch
            case e: Exception =>
              throw new RuntimeException(
                s"Failed to convert map to ${$typeName} for parameter '$paramName'",
                e
              )
    }

  private def derivedSum[T: Type](
      mirror: Expr[Mirror.SumOf[T]]
  )(using Quotes): Expr[JacksonConverter[T]] =
    import quotes.reflect.*

    val tpe = TypeRepr.of[T]
    val typeName = Expr(tpe.typeSymbol.name)
    val ct = Expr
      .summon[ClassTag[T]]
      .getOrElse(
        report.errorAndAbort(s"No ClassTag available for ${tpe.typeSymbol.name}")
      )

    '{
      given ClassTag[T] = $ct
      new JacksonConverter[T]:
        def convert(name: String, rawValue: Any, mapper: JsonMapper & ClassTagExtensions): T =
          if rawValue == null then
            throw new RuntimeException(
              s"Null value provided for parameter '$name' of type ${$typeName}"
            )

          // For sum types (sealed traits/enums), delegate to Jackson
          try mapper.convertValue[T](rawValue)
          catch
            case e: Exception =>
              throw new RuntimeException(
                s"Failed to convert value for parameter '$name' to type ${$typeName}. Value: $rawValue",
                e
              )
    }

  /** Derives JacksonConverter instances for common container types */
  object containers:

    inline def seq[A](using conv: JacksonConverter[A], ct: ClassTag[A]): JacksonConverter[Seq[A]] =
      summon[JacksonConverter[Seq[A]]]

    inline def list[A](using
        conv: JacksonConverter[A],
        ct: ClassTag[A]
    ): JacksonConverter[List[A]] =
      summon[JacksonConverter[List[A]]]

    inline def option[A](using
        conv: JacksonConverter[A],
        ct: ClassTag[A]
    ): JacksonConverter[Option[A]] =
      summon[JacksonConverter[Option[A]]]

    inline def map[K, V](using
        kConv: JacksonConverter[K],
        vConv: JacksonConverter[V],
        kCt: ClassTag[K],
        vCt: ClassTag[V]
    ): JacksonConverter[Map[K, V]] =
      summon[JacksonConverter[Map[K, V]]]
