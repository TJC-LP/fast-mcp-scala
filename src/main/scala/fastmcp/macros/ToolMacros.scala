package fastmcp.macros

import fastmcp.server.FastMCPScala

import scala.quoted.*
import scala.reflect.ClassTag

/**
 * Minimal macro for scanning @Tool methods, building a naive JSON schema, and registering with the server.
 *
 * Steps:
 *  1. Find methods with @Tool.
 *     2. Extract (name, description) from the annotation.
 *     3. Gather each parameter's name & type (as a string).
 *     4. Build a naive JSON schema: { type: "object", properties: {...}, required: [...] }
 *     5. Call server.registerMacroTool( ... ) with that info.
 */
object ToolMacros:

  /**
   * Entry point: called by something like:
   * inline def scanAnnotations[T]: Unit = ToolMacros.processAnnotations[T](server)
   */
  inline def processAnnotations[T](server: FastMCPScala): Unit =
    ${ processAnnotationsImpl[T]('server) }

  /**
   * The macro implementation that inspects type T, finds @Tool methods, and registers them.
   */
  private def processAnnotationsImpl[T: Type](serverExpr: Expr[FastMCPScala])(using quotes: Quotes): Expr[Unit] =
    import quotes.reflect.*

    // Explicitly summon ClassTag at macro expansion site
    Expr.summon[ClassTag[T]] match
      case Some(ctagExpr) =>
        // Now use ctagExpr as your ClassTag[T]
        val tSymbol = TypeRepr.of[T].typeSymbol
        if !tSymbol.isClassDef then
          return '{ () }

        val methods = tSymbol.declaredMethods

        val registrations = methods.flatMap { methodSym =>
          val maybeToolAnnot = methodSym.annotations.find(ann =>
            ann.tpe.derivesFrom(TypeRepr.of[fastmcp.core.Tool].typeSymbol)
          )

          maybeToolAnnot match
            case Some(annot) =>
              val (toolName, toolDesc) = parseToolAnnotation(quotes)(annot)
              val finalName = if toolName.nonEmpty then toolName else methodSym.name
              val finalDesc = if toolDesc.nonEmpty then Some(toolDesc) else None

              val (paramNames, paramTypes, requiredFlags) =
                extractMethodParameters(quotes)(methodSym)

              val schemaJson = buildJsonSchema(paramNames, paramTypes, requiredFlags)

              val nameExpr = Expr(finalName)
              val descExpr = finalDesc match
                case Some(desc) => '{ Some(${ Expr(desc) }) }
                case None => '{ None }
              val methodNameExpr = Expr(methodSym.name)
              val schemaJsonExpr = Expr(schemaJson)
              val paramNamesExpr = Expr(paramNames)
              val paramTypesExpr = Expr(paramTypes)
              val requiredFlagsExpr = Expr(requiredFlags)

              Some {
                '{
                  $serverExpr.registerMacroTool[T](
                    $nameExpr,
                    $descExpr,
                    $methodNameExpr,
                    $schemaJsonExpr,
                    $paramNamesExpr,
                    $paramTypesExpr,
                    $requiredFlagsExpr
                  )(using $ctagExpr)
                }
              }

            case None => None
        }

        if registrations.isEmpty then '{ () }
        else Expr.block(registrations.toList, '{ () })

      case None =>
        quotes.reflect.report.errorAndAbort(s"No ClassTag available for ${Type.show[T]}")

  // -- Helper: parse annotation arguments from @Tool(...)
  private def parseToolAnnotation(q: Quotes)(annotTerm: q.reflect.Term): (String, String) =
    import q.reflect.*
    // annotation is Tool(...) with named or unnamed params
    // We only care about the first two: name, description
    val args = annotTerm match
      case Apply(_, argList) => argList
      case _ => Nil

    val nameVal = args.lift(0).flatMap(extractOptionString(q)).getOrElse("")
    val descVal = args.lift(1).flatMap(extractOptionString(q)).getOrElse("")
    (nameVal, descVal)

  // Attempt to read `Option[String](...)`
  private def extractOptionString(q: Quotes)(term: q.reflect.Term): Option[String] =
    import q.reflect.*
    term match
      // Could be Some("value") or None
      case Typed(Apply(_, List(Literal(StringConstant(stringVal)))), _) => Some(stringVal)
      case Literal(StringConstant(stringVal)) => stringVal.nonEmptyOption
      case _ => None

  // -- Extract method param info: returns (List[paramNames], List[paramTypes], List[Boolean for required?])
  private def extractMethodParameters(q: Quotes)(methodSym: q.reflect.Symbol): (List[String], List[String], List[Boolean]) =

    val paramLists = methodSym.paramSymss
    // We'll flatten them and ignore multiple param lists for now
    val allParams = paramLists.flatten

    // For each param, get name, type, "required" (we consider everything required for now)
    val results = allParams.map { pSym =>
      val pName = pSym.name
      val pType = pSym.typeRef.show
      // We'll treat everything as required for now
      val required = true
      (pName, pType, required)
    }
    val (names, types, reqFlags) = results.unzip3
    (names, types, reqFlags)

  // -- Build a naive JSON schema from param names & types
  private def buildJsonSchema(
                               paramNames: List[String],
                               paramTypes: List[String],
                               requiredFlags: List[Boolean]
                             ): String =
    // We'll map basic type strings -> JSON
    val propEntries = paramNames.zip(paramTypes).zip(requiredFlags).map {
      case ((pName, pType), req) =>
        val jType =
          if pType.contains("Int") || pType.contains("Long") then "integer"
          else if pType.contains("Double") || pType.contains("Float") then "number"
          else if pType.contains("Boolean") then "boolean"
          else "string"
        s""""$pName": { "type": "$jType" }"""
    }.mkString(",\n")

    val requiredList = paramNames.zip(requiredFlags)
      .collect { case (n, true) => s""""$n"""" }
      .mkString(",")

    s"""{
       |  "type": "object",
       |  "properties": {
       |    $propEntries
       |  },
       |  "required": [ $requiredList ]
       |}""".stripMargin

extension (s: String)
  def nonEmptyOption: Option[String] =
    if s.trim.isEmpty then None else Some(s)