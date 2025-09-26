package com.tjclp.fastmcp
package macros

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.server.*
import com.tjclp.fastmcp.server.manager.*
import zio.*

import scala.quoted.*

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

    // Collect @Param/@ToolParam descriptions so we can inject them into the schema
    val paramDescriptions: Map[String, String] =
      methodSym.paramSymss.headOption
        .getOrElse(Nil)
        .flatMap { pSym =>
          MacroUtils
            .extractParamAnnotation(pSym, Some("Tool"))
            .flatMap { annotTerm =>
              // description is either the first String literal or the named arg "description"
              annotTerm match
                case Apply(_, args) =>
                  args
                    .collectFirst { case NamedArg("description", Literal(StringConstant(d))) =>
                      d
                    }
                    .orElse {
                      args.collectFirst { case Literal(StringConstant(d)) => d }
                    }
                case _ => None
            }
            .map(pSym.name -> _)
        }
        .toMap

    val schemaWithDescriptions: Expr[io.circe.Json] = {
      if paramDescriptions.isEmpty then rawSchema
      else '{ MacroUtils.injectParamDescriptions($rawSchema, ${ Expr(paramDescriptions) }) }
    }

    // 7️⃣  Compose registration effect --------------------------------------------------------
    val registration: Expr[ZIO[Any, Throwable, FastMcpServer]] = '{
      java.lang.System.err.println("[ToolProcessor] registering tool: " + ${ Expr(finalName) })
      $server.tool(
        name = ${ Expr(finalName) },
        description = ${ Expr(finalDesc) },
        handler = $handler,
        inputSchema = Right($schemaWithDescriptions.spaces2)
      )
    }

    // 8️⃣  Run effect & return server ---------------------------------------------------------
    runAndReturnServer(server)(registration)
