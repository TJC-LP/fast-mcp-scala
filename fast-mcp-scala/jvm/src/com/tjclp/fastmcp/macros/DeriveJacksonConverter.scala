package com.tjclp.fastmcp.macros

import scala.deriving.Mirror
import scala.quoted.*
import scala.reflect.ClassTag

/** Macro to automatically derive JacksonConverter instances for case classes and Scala 3 enums. */
@SuppressWarnings(Array("org.wartremover.warts.Null"))
object DeriveJacksonConverter:

  /** Derives a JacksonConverter for a case class `T` or Scala 3 enum `T`. */
  @scala.annotation.nowarn("msg=unused implicit parameter")
  inline def derived[T](using Mirror.Of[T], ClassTag[T]): JacksonConverter[T] =
    ${ derivedImpl[T] }

  private def derivedImpl[T: Type](using Quotes): Expr[JacksonConverter[T]] =
    import quotes.reflect.*

    val tpe = TypeRepr.of[T]
    val typeSymbol = tpe.typeSymbol

    if typeSymbol.isClassDef && typeSymbol.flags.is(Flags.Case) then derivedProduct[T]
    else
      Expr.summon[Mirror.SumOf[T]] match
        case Some(_) => derivedSum[T]
        case None =>
          report.errorAndAbort(
            s"Can only derive JacksonConverter for case classes or Scala 3 enums, but ${typeSymbol.name} is unsupported"
          )

  private def derivedProduct[T: Type](using Quotes): Expr[JacksonConverter[T]] =
    import quotes.reflect.*

    val tpe = TypeRepr.of[T]
    val typeSymbol = tpe.typeSymbol
    val typeName = Expr(typeSymbol.name)
    val ct = Expr
      .summon[ClassTag[T]]
      .getOrElse(report.errorAndAbort(s"No ClassTag available for ${typeSymbol.name}"))
    val ctorParams = typeSymbol.primaryConstructor.paramSymss.flatten
    val companion = typeSymbol.companionModule

    def defaultValueExpr[F: Type](index: Int): Option[Expr[F]] =
      if companion == Symbol.noSymbol then None
      else
        val candidateNames = List(
          s"$$lessinit$$greater$$default$$${index + 1}",
          s"apply$$default$$${index + 1}"
        )
        candidateNames.view
          .flatMap(name => companion.methodMember(name))
          .headOption
          .map { sym =>
            Select(Ref(companion), sym).asExprOf[F]
          }

    def converterExpr(fieldType: TypeRepr)(using Quotes): Expr[JacksonConverter[?]] =
      fieldType.asType match
        case '[f] =>
          Expr
            .summon[JacksonConverter[f]]
            .getOrElse(
              report.errorAndAbort(
                s"No JacksonConverter in scope for field type: ${fieldType.show}"
              )
            )

    def fieldExpr(
        param: Symbol,
        index: Int,
        mapExpr: Expr[Map[String, Any]],
        contextExpr: Expr[JacksonConversionContext]
    )(using Quotes): Expr[Any] =
      val fieldNameExpr = Expr(param.name)
      val fieldType = param.info

      fieldType.asType match
        case '[f] =>
          val convExpr = converterExpr(fieldType).asExprOf[JacksonConverter[f]]

          defaultValueExpr[f](index) match
            case Some(defaultExpr) =>
              '{
                val fieldName = $fieldNameExpr
                $mapExpr.get(fieldName) match
                  case Some(value) => $convExpr.convert(fieldName, value, $contextExpr)
                  case None => $defaultExpr
              }.asExprOf[Any]

            case None =>
              fieldType match
                case AppliedType(base, _)
                    if base.typeSymbol == TypeRepr.of[Option[Any]].typeSymbol =>
                  '{
                    val fieldName = $fieldNameExpr
                    $mapExpr.get(fieldName) match
                      case Some(value) => $convExpr.convert(fieldName, value, $contextExpr)
                      case None => None
                  }.asExprOf[Any]

                case _ =>
                  '{
                    val fieldName = $fieldNameExpr
                    val raw = $mapExpr.getOrElse(
                      fieldName,
                      throw new NoSuchElementException("Key not found in map: " + fieldName)
                    )
                    $convExpr.convert(fieldName, raw, $contextExpr)
                  }.asExprOf[Any]

    val fromMapExpr: Expr[(Map[String, Any], JacksonConversionContext) => T] =
      '{ (map: Map[String, Any], context: JacksonConversionContext) =>
        ${
          val argTerms = ctorParams.zipWithIndex.map { case (param, idx) =>
            fieldExpr(param, idx, 'map, 'context).asTerm
          }
          Apply(Select(New(TypeTree.of[T]), typeSymbol.primaryConstructor), argTerms).asExprOf[T]
        }
      }

    '{
      given ClassTag[T] = $ct
      new JacksonConverter[T]:
        private val fromMap = $fromMapExpr

        def convert(name: String, rawValue: Any, context: JacksonConversionContext): T =
          if rawValue == null then
            throw new RuntimeException(
              s"Null value provided for parameter '$name' of type ${$typeName}"
            )

          rawValue match
            case t: T => t
            case map: Map[String @unchecked, Any @unchecked] =>
              fromMap(map, context)
            case jMap: java.util.Map[?, ?] =>
              import scala.jdk.CollectionConverters.*
              fromMap(jMap.asScala.toMap.asInstanceOf[Map[String, Any]], context)
            case json: String =>
              fromMap(context.parseJsonObject(name, json), context)
            case _ =>
              throw new RuntimeException(
                s"Failed to convert value for parameter '$name' to type ${$typeName}. Value: $rawValue"
              )
    }

  private def derivedSum[T: Type](using Quotes): Expr[JacksonConverter[T]] =
    import quotes.reflect.*

    val tpe = TypeRepr.of[T]
    val typeName = Expr(tpe.typeSymbol.name)
    val ct = Expr
      .summon[ClassTag[T]]
      .getOrElse(report.errorAndAbort(s"No ClassTag available for ${tpe.typeSymbol.name}"))

    '{
      given ClassTag[T] = $ct
      new JacksonConverter[T]:
        private lazy val values: IndexedSeq[T] =
          val runtimeClass = summon[ClassTag[T]].runtimeClass
          try
            val method = runtimeClass.getMethod("values")
            method
              .invoke(null)
              .asInstanceOf[Array[Any]]
              .iterator
              .map(_.asInstanceOf[T])
              .toIndexedSeq
          catch
            case _: ReflectiveOperationException =>
              throw new RuntimeException(
                s"Cannot derive JacksonConverter for ${$typeName}: only enum-like sums with a values() method are supported"
              )

        def convert(name: String, rawValue: Any, context: JacksonConversionContext): T =
          if rawValue == null then
            throw new RuntimeException(
              s"Null value provided for parameter '$name' of type ${$typeName}"
            )

          rawValue match
            case t: T => t
            case s: String =>
              values
                .find(_.toString == s)
                .getOrElse(
                  throw new RuntimeException(
                    s"Cannot parse '$s' as ${$typeName} for parameter '$name'"
                  )
                )
            case n: Number =>
              values
                .lift(n.intValue())
                .getOrElse(
                  throw new RuntimeException(
                    s"Cannot parse ordinal '${n.intValue()}' as ${$typeName} for parameter '$name'"
                  )
                )
            case _ =>
              context.convertValue[T](name, rawValue)
    }

  /** Derives JacksonConverter instances for common container types. */
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
