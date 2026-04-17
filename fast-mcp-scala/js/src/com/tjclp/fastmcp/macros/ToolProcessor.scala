package com.tjclp.fastmcp
package macros

import scala.quoted.*

import zio.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.server.*
import com.tjclp.fastmcp.server.manager.*

/** JS-target tool annotation processor. */
private[macros] object ToolProcessor extends AnnotationProcessorBase:

  def processToolAnnotation(using Quotes)(
      server: Expr[JsMcpServer],
      ownerSym: quotes.reflect.Symbol,
      methodSym: quotes.reflect.Symbol
  ): Expr[JsMcpServer] =
    import quotes.reflect.*

    val methodName = methodSym.name

    val toolAnnot = findAnnotation[Tool](methodSym).getOrElse {
      report.errorAndAbort(s"No @Tool annotation found on method '$methodName'")
    }

    val (finalName, finalDesc) = nameAndDescription(toolAnnot, methodSym)

    val (
      hintTitle,
      hintReadOnly,
      hintDestructive,
      hintIdempotent,
      hintOpenWorld,
      hintReturnDirect
    ) =
      MacroUtils.parseToolAnnotationHints(toolAnnot)

    val hasAnyAnnotation = List(
      hintTitle,
      hintReadOnly,
      hintDestructive,
      hintIdempotent,
      hintOpenWorld,
      hintReturnDirect
    ).exists(_.isDefined)

    val annotationsExpr: Expr[Option[com.tjclp.fastmcp.core.ToolAnnotations]] =
      if !hasAnyAnnotation then '{ None }
      else
        '{
          Some(
            com.tjclp.fastmcp.core.ToolAnnotations(
              title = ${ optionStringExpr(hintTitle) },
              readOnlyHint = ${ optionBoolExpr(hintReadOnly) },
              destructiveHint = ${ optionBoolExpr(hintDestructive) },
              idempotentHint = ${ optionBoolExpr(hintIdempotent) },
              openWorldHint = ${ optionBoolExpr(hintOpenWorld) },
              returnDirect = ${ optionBoolExpr(hintReturnDirect) }
            )
          )
        }

    val methodRefExpr = methodRef(ownerSym, methodSym)

    val ctxParamPresent = methodSym.paramSymss.headOption.exists(_.exists { p =>
      p.name == "ctx" && p.info <:< TypeRepr.of[McpContext]
    })

    val handler: Expr[ContextualToolHandler] = '{
      (args: Map[String, Any], ctxOpt: Option[McpContext]) =>
        ZIO.attempt {
          val patchedArgs =
            if ${ Expr(ctxParamPresent) } then args + ("ctx" -> ctxOpt.getOrElse(McpContext.empty))
            else args

          MapToFunctionMacro
            .callByMap($methodRefExpr)
            .asInstanceOf[Map[String, Any] => Any](patchedArgs)
        }
    }

    val rawSchema: Expr[io.circe.Json] = '{
      JsonSchemaMacro.schemaForFunctionArgs(
        $methodRefExpr,
        ${ Expr(if ctxParamPresent then List("ctx") else Nil) }
      )
    }

    val params = methodSym.paramSymss.headOption.getOrElse(Nil)
    val defaultMethodOwner =
      if ownerSym.flags.is(Flags.Module) then ownerSym
      else ownerSym.companionModule

    val paramsWithDefaults: Set[String] = params.zipWithIndex.collect {
      case (pSym, idx)
          if defaultMethodOwner != Symbol.noSymbol &&
            defaultMethodOwner.declaredMethod(s"${methodSym.name}$$default$$${idx + 1}").nonEmpty =>
        pSym.name
    }.toSet

    val paramMetadata: List[(String, ParamMetadata)] =
      params.flatMap { pSym =>
        MacroUtils
          .extractParamAnnotation(pSym)
          .map { annotTerm =>
            val (desc, examples, required, schema) = MacroUtils.parseToolParam(Some(annotTerm))

            if !required then
              val isOptionType = pSym.info <:< TypeRepr.of[Option[?]]
              val hasDefault = paramsWithDefaults.contains(pSym.name)

              if !isOptionType && !hasDefault then
                report.errorAndAbort(
                  s"Parameter '${pSym.name}' in method '$methodName' is marked as required=false " +
                    s"but is not an Option type and has no default value. " +
                    s"Use Option[${pSym.info.show}] or provide a default value."
                )

            pSym.name -> ParamMetadata(desc, examples, required, schema)
          }
      }

    val schemaWithMetadata: Expr[io.circe.Json] =
      if paramMetadata.isEmpty then rawSchema
      else
        val metadataEntries: List[Expr[(String, ParamMetadata)]] = paramMetadata.map {
          case (name, meta) =>
            val examplesExprs = meta.examples.map(Expr(_))
            '{
              (
                ${ Expr(name) },
                ParamMetadata(
                  ${ Expr(meta.description) },
                  List(${ Varargs(examplesExprs) }*),
                  ${ Expr(meta.required) },
                  ${ Expr(meta.schema) }
                )
              )
            }
        }
        val metadataMapExpr: Expr[Map[String, ParamMetadata]] = '{
          Map(${ Varargs(metadataEntries) }*)
        }
        '{ MacroUtils.injectParamMetadata($rawSchema, $metadataMapExpr) }

    val registration: Expr[ZIO[Any, Throwable, McpServerPlatform]] = '{
      java.lang.System.err.println("[ToolProcessor] registering tool: " + ${ Expr(finalName) })
      $server.tool(
        name = ${ Expr(finalName) },
        description = ${ Expr(finalDesc) },
        handler = $handler,
        inputSchema = ToolInputSchema.unsafeFromJsonString($schemaWithMetadata.spaces2),
        annotations = $annotationsExpr
      )
    }

    runAndReturnServer(server)(registration)

  private def optionStringExpr(using Quotes)(opt: Option[String]): Expr[Option[String]] =
    opt match
      case Some(s) => '{ Some(${ Expr(s) }) }
      case None => '{ None }

  private def optionBoolExpr(using Quotes)(opt: Option[Boolean]): Expr[Option[Boolean]] =
    opt match
      case Some(b) => '{ Some(${ Expr(b) }) }
      case None => '{ None }
