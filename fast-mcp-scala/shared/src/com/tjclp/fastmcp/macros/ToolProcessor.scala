package com.tjclp.fastmcp
package macros

import scala.quoted.*

import zio.*
import zio.json.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.server.*
import com.tjclp.fastmcp.server.manager.*

/** Cross-platform `@Tool` annotation processor. Emits a registration expression targeting the
  * shared [[McpServerCore]] trait — argument decoding resolves through the shared zio-json
  * derivation (`McpDecoders` over `DefaultDecodeContext`), identical on JVM and Scala.js.
  */
private[macros] object ToolProcessor extends AnnotationProcessorBase:

  /** The name this method registers under — identical to what [[processToolAnnotation]] uses, so
    * the compile-time collision check in [[RegistrationMacro]] can never diverge from it.
    */
  def registeredName(using Quotes)(methodSym: quotes.reflect.Symbol): String =
    import quotes.reflect.*
    val annot = findAnnotation[Tool](methodSym).getOrElse(
      report.errorAndAbort(s"No @Tool annotation found on method '${methodSym.name}'")
    )
    nameAndDescription(annot, methodSym)._1

  def processToolAnnotation[R: Type](using Quotes)(
      server: Expr[McpServerCore[R]],
      ownerSym: quotes.reflect.Symbol,
      methodSym: quotes.reflect.Symbol
  ): Expr[McpServerCore[R]] =
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
      hintReturnDirect,
      hintTaskSupport
    ) =
      MacroUtils.parseToolAnnotationHints(toolAnnot)

    val taskSupportExpr: Expr[Option[com.tjclp.fastmcp.core.TaskSupport]] = hintTaskSupport match
      case None => '{ None }
      case Some("forbidden") => '{ Some(com.tjclp.fastmcp.core.TaskSupport.Forbidden) }
      case Some("optional") => '{ Some(com.tjclp.fastmcp.core.TaskSupport.Optional) }
      case Some("required") => '{ Some(com.tjclp.fastmcp.core.TaskSupport.Required) }
      case Some(other) =>
        report.errorAndAbort(
          s"@Tool taskSupport must be one of \"forbidden\" | \"optional\" | \"required\" — got \"$other\""
        )

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

    val ctxParamPresentExpr = Expr(ctxParamPresent)

    val effectShape = MacroUtils.detectEffectShape(methodSym)

    val rawSchema: Expr[zio.json.ast.Json] = '{
      JsonSchemaMacro.schemaForFunctionArgs(
        $methodRefExpr,
        ${ Expr(if ctxParamPresent then List("ctx") else Nil) }
      )
    }

    val params = methodSym.paramSymss.headOption.getOrElse(Nil)

    // `HasDefault` is set on the annotated method's OWN parameter symbols (pickled, so it survives
    // separate compilation), so an overloaded sibling's `f$default$N` getters — which are name +
    // index based — can no longer be attributed to this method.
    val paramsWithDefaults: Set[String] =
      params.filter(_.flags.is(Flags.HasDefault)).map(_.name).toSet

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

    val schemaWithMetadata: Expr[zio.json.ast.Json] =
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

    // The method's required ZIO environment. `Any` for non-ZIO returns (pure / Try / Either) —
    // those handlers don't require an environment, which is a `ZIO[Any, ...]` subtype of any
    // `ZIO[R, ...]` by contravariance. For ZIO returns this is `R1` from `ZIO[R1, E, A]`.
    val rMethodType: Type[?] = MacroUtils
      .extractZioRequirement(methodSym)
      .map(_.asType)
      .getOrElse(TypeRepr.of[Any].asType)

    // Pre-flight R-check with a friendlier error than Scala's bound-violation message.
    val rServerRepr = TypeRepr.of[R]
    val rMethodRepr = rMethodType match { case '[t] => TypeRepr.of[t] }
    if !(rServerRepr <:< rMethodRepr) then
      report.errorAndAbort(
        s"@Tool '$methodName' requires ZIO environment '${rMethodRepr.show}' but the server's " +
          s"environment type is '${rServerRepr.show}', which does not provide it. " +
          s"Construct the server as McpServer.typed[${rServerRepr.show} & ${rMethodRepr.show}] " +
          s"(or wider), or remove the environment requirement from the method body."
      )

    // Handler typed at the method's required environment. Scala's normal type-checker enforces
    // that the surrounding `server.tool[R1 >: R](handler: ContextualToolHandler[R1])` constraint
    // is satisfied — but we've already verified that above with a friendlier message.
    val registration: Expr[ZIO[Any, Throwable, McpServerCore[R]]] = rMethodType match
      case '[rMethod] =>
        val handler: Expr[ContextualToolHandler[rMethod]] = effectShape match
          case MacroUtils.EffectShape.Pure =>
            '{ (args: Map[String, Any], ctxOpt: Option[McpContext]) =>
              ZIO.attempt {
                val patchedArgs =
                  if $ctxParamPresentExpr then args + ("ctx" -> ctxOpt.getOrElse(McpContext.empty))
                  else args
                MapToFunctionMacro
                  .callByMap($methodRefExpr)
                  .asInstanceOf[Map[String, Any] => Any](patchedArgs)
              }
            }

          case MacroUtils.EffectShape.Zio =>
            '{ (args: Map[String, Any], ctxOpt: Option[McpContext]) =>
              ZIO.suspend {
                val patchedArgs =
                  if $ctxParamPresentExpr then args + ("ctx" -> ctxOpt.getOrElse(McpContext.empty))
                  else args
                MapToFunctionMacro
                  .callByMap($methodRefExpr)
                  .asInstanceOf[Map[String, Any] => Any](patchedArgs)
                  .asInstanceOf[ZIO[rMethod, Any, Any]]
                  .mapError {
                    case t: Throwable => t
                    case other => new RuntimeException(s"Tool error: $other")
                  }
              }
            }

          case MacroUtils.EffectShape.TryEffect =>
            '{ (args: Map[String, Any], ctxOpt: Option[McpContext]) =>
              ZIO.suspend {
                val patchedArgs =
                  if $ctxParamPresentExpr then args + ("ctx" -> ctxOpt.getOrElse(McpContext.empty))
                  else args
                val result = MapToFunctionMacro
                  .callByMap($methodRefExpr)
                  .asInstanceOf[Map[String, Any] => Any](patchedArgs)
                  .asInstanceOf[scala.util.Try[Any]]
                ZIO.fromTry(result)
              }
            }

          case MacroUtils.EffectShape.EitherThrowable =>
            '{ (args: Map[String, Any], ctxOpt: Option[McpContext]) =>
              ZIO.suspend {
                val patchedArgs =
                  if $ctxParamPresentExpr then args + ("ctx" -> ctxOpt.getOrElse(McpContext.empty))
                  else args
                val result = MapToFunctionMacro
                  .callByMap($methodRefExpr)
                  .asInstanceOf[Map[String, Any] => Any](patchedArgs)
                  .asInstanceOf[Either[Throwable, Any]]
                ZIO.fromEither(result)
              }
            }

        // Cast the handler to `ContextualToolHandler[R]`. The macro has already verified
        // `R <:< rMethod` above, so the cast is sound at runtime: providing `R` to an effect
        // that may require `rMethod` works because the server's environment is at least as wide
        // as the handler's. The cast is only needed to side-step a macro-time variance gap
        // (Scala can't prove the bound `R1 >: R` for `R1 = rMethod` without per-call evidence).
        '{
          $server.tool(
            definition = ToolDefinition(
              name = ${ Expr(finalName) },
              description = ${ Expr(finalDesc) },
              inputSchema = ToolInputSchema.unsafeFromJsonString($schemaWithMetadata.toJson),
              annotations = $annotationsExpr,
              taskSupport = $taskSupportExpr
            ),
            handler =
              $handler.asInstanceOf[com.tjclp.fastmcp.server.manager.ContextualToolHandler[R]],
            options = com.tjclp.fastmcp.server.manager.ToolRegistrationOptions()
          )
        }

    runAndReturnServer[R](server)(registration)

  private def optionStringExpr(using Quotes)(opt: Option[String]): Expr[Option[String]] =
    opt match
      case Some(s) => '{ Some(${ Expr(s) }) }
      case None => '{ None }

  private def optionBoolExpr(using Quotes)(opt: Option[Boolean]): Expr[Option[Boolean]] =
    opt match
      case Some(b) => '{ Some(${ Expr(b) }) }
      case None => '{ None }
