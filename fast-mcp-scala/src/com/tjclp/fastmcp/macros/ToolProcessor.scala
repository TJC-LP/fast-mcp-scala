package com.tjclp.fastmcp
package macros

import scala.quoted.*

import zio.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.server.*
import com.tjclp.fastmcp.server.manager.*

/** Refactored implementation that relies on [[AnnotationProcessorBase]] for the repetitive work so
  * this file only has to deal with Tool‑specific details (context injection & JSON‑schema tweaks).
  */
private[macros] object ToolProcessor extends AnnotationProcessorBase:

  def processToolAnnotation(using Quotes)(
      server: Expr[FastMcpServer],
      ownerSym: quotes.reflect.Symbol,
      methodSym: quotes.reflect.Symbol
  ): Expr[FastMcpServer] =
    import quotes.reflect.*

    val methodName = methodSym.name

    // 1️⃣  Locate the @Tool annotation ---------------------------------------------------------
    val toolAnnot = findAnnotation[Tool](methodSym).getOrElse {
      report.errorAndAbort(s"No @Tool annotation found on method '$methodName'")
    }

    // 2️⃣  Extract name / description (with Scaladoc fallback) --------------------------------
    val (finalName, finalDesc) = nameAndDescription(toolAnnot, methodSym)

    // 3️⃣  Stable method reference -------------------------------------------------------------
    val methodRefExpr = methodRef(ownerSym, methodSym)

    // 4️⃣  Detect an optional ctx: McpContext parameter ----------------------------------------
    val ctxParamPresent = methodSym.paramSymss.headOption.exists(_.exists { p =>
      p.name == "ctx" && p.info <:< TypeRepr.of[McpContext]
    })

    // 5️⃣  Build the contextual handler -------------------------------------------------------
    val handler: Expr[ContextualToolHandler] = '{
      (args: Map[String, Any], ctxOpt: Option[McpContext]) =>
        ZIO.attempt {
          val patchedArgs =
            if ${ Expr(ctxParamPresent) } then args + ("ctx" -> ctxOpt.getOrElse(McpContext()))
            else args

          MapToFunctionMacro
            .callByMap($methodRefExpr)
            .asInstanceOf[Map[String, Any] => Any](patchedArgs)
        }
    }

    // 6️⃣  Auto‑generate JSON schema & inject @ToolParam descriptions --------------------------
    val rawSchema: Expr[io.circe.Json] = '{
      JsonSchemaMacro.schemaForFunctionArgs(
        $methodRefExpr,
        ${ Expr(if ctxParamPresent then List("ctx") else Nil) }
      )
    }

    // Collect all @Param/@ToolParam metadata (description, examples, required, schema)
    // Also validate that required=false is only used with Option types or default values
    val params = methodSym.paramSymss.headOption.getOrElse(Nil)

    // Find which parameters have default values
    // For objects (modules), default methods are on the object itself
    // For classes, they're on the companion module
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
          .extractParamAnnotation(pSym, Some("Tool"))
          .map { annotTerm =>
            val (desc, examples, required, schema) = MacroUtils.parseToolParam(Some(annotTerm))

            // Validate: required=false must have Option type or default value
            if (!required) {
              val isOptionType = pSym.info <:< TypeRepr.of[Option[?]]
              val hasDefault = paramsWithDefaults.contains(pSym.name)

              if (!isOptionType && !hasDefault) {
                report.errorAndAbort(
                  s"Parameter '${pSym.name}' in method '$methodName' is marked as required=false " +
                    s"but is not an Option type and has no default value. " +
                    s"Use Option[${pSym.info.show}] or provide a default value."
                )
              }
            }

            pSym.name -> ParamMetadata(desc, examples, required, schema)
          }
      }

    // Convert compile-time metadata to runtime expression
    val schemaWithMetadata: Expr[io.circe.Json] = {
      if paramMetadata.isEmpty then rawSchema
      else {
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
      }
    }

    // 7️⃣  Compose registration effect --------------------------------------------------------
    val registration: Expr[ZIO[Any, Throwable, FastMcpServer]] = '{
      java.lang.System.err.println("[ToolProcessor] registering tool: " + ${ Expr(finalName) })
      $server.tool(
        name = ${ Expr(finalName) },
        description = ${ Expr(finalDesc) },
        handler = $handler,
        inputSchema = Right($schemaWithMetadata.spaces2)
      )
    }

    // 8️⃣  Run effect & return server ---------------------------------------------------------
    runAndReturnServer(server)(registration)
