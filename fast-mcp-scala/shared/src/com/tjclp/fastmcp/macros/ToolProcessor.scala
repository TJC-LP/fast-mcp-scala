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

    requireRegistrableShape("@Tool", methodSym)

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
          s"@Tool(taskSupport) on method '$methodName' must be one of \"forbidden\" | " +
            s"\"optional\" | \"required\" — got \"$other\"",
          methodPosition(methodSym)
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

    // The parameter that receives the request's McpContext, recognised by type (any name). It is
    // left out of the schema and filled in by the handler below.
    val ctxParamName: Option[String] = contextParameter("@Tool", methodSym)

    val effectShape = MacroUtils.detectEffectShape(methodSym)

    val rawSchema: Expr[zio.json.ast.Json] = '{
      JsonSchemaMacro.schemaForFunctionArgs(
        $methodRefExpr,
        ${ Expr(ctxParamName.toList) }
      )
    }

    // The MCP arguments: every parameter except the injected context.
    val params = methodSym.paramSymss.headOption.getOrElse(Nil).filterNot { p =>
      ctxParamName.contains(p.name)
    }

    // `HasDefault` is set on the annotated method's OWN parameter symbols (pickled, so it survives
    // separate compilation), so an overloaded sibling's `f$default$N` getters — which are name +
    // index based — can no longer be attributed to this method.
    val paramsWithDefaults: Set[String] =
      params.filter(_.flags.is(Flags.HasDefault)).map(_.name).toSet

    // The subset whose default the generated handler really applies on omission — the list
    // `MapToFunctionMacro` builds the handler from. Only these may be advertised as optional.
    val appliedDefaults: Set[String] = MacroUtils.appliedDefaultGetters(methodSym).keySet

    // One entry per parameter whose schema differs from what `schemaForFunctionArgs` derives from
    // the type alone: every `@Param`-annotated parameter, plus every un-annotated non-`Option`
    // parameter with an applied default (the function type does not carry defaults, so the raw
    // schema lists it in `required`; the entry takes it out). A defaulted parameter is required
    // only when `@Param(required = true)` is written (TJC-5117).
    val paramMetadata: List[(String, ParamMetadata)] =
      params.flatMap { pSym =>
        val isOptionType = pSym.termRef.widenTermRefByName <:< TypeRepr.of[Option[?]]
        val hasAppliedDefault = appliedDefaults.contains(pSym.name)
        MacroUtils.extractParamAnnotation(pSym) match
          case Some(annotTerm) =>
            val (desc, examples, required, schema) =
              MacroUtils.parseToolParam(Some(annotTerm), isOptionType || hasAppliedDefault)

            if !required then
              val hasDefault = paramsWithDefaults.contains(pSym.name)

              if !isOptionType && !hasDefault then
                val shownType = (tpe: TypeRepr) => tpe.show(using Printer.TypeReprShortCode)
                repeatedElementType(pSym) match
                  case Some(element) =>
                    // A repeated parameter can be neither: there is no `Option[T*]` and it
                    // cannot have a default. Its type used to be printed with the compiler's
                    // internal `@Repeated` annotation.
                    report.errorAndAbort(
                      s"Parameter '${pSym.name}' in method '$methodName' is marked as " +
                        s"required=false but is a repeated parameter (${shownType(element)}*), " +
                        "which can be neither an Option nor have a default value. Remove " +
                        s"required = false, or declare it as `${pSym.name}: " +
                        s"List[${shownType(element)}] = Nil`.",
                      methodPosition(methodSym)
                    )
                  case None =>
                    report.errorAndAbort(
                      s"Parameter '${pSym.name}' in method '$methodName' is marked as required=false " +
                        s"but is not an Option type and has no default value. " +
                        s"Use Option[${shownType(pSym.termRef.widenTermRefByName)}] or provide a " +
                        "default value.",
                      methodPosition(methodSym)
                    )

            Some(pSym.name -> ParamMetadata(desc, examples, required, schema))
          case None if hasAppliedDefault && !isOptionType =>
            Some(pSym.name -> ParamMetadata(required = false))
          case None => None
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
    val rMethodRepr = rMethodType match { case '[t] => TypeRepr.of[t] }
    requireEnvironment[R]("@Tool", methodSym, rMethodRepr)

    // The decoded-argument map the method is called with: the wire arguments, plus the context
    // under the name of the parameter that takes it.
    val withContext: Expr[(Map[String, Any], Option[McpContext]) => Map[String, Any]] =
      ctxParamName match
        case Some(name) =>
          '{ (args: Map[String, Any], ctxOpt: Option[McpContext]) =>
            args + (${ Expr(name) } -> ctxOpt.getOrElse(McpContext.empty))
          }
        case None => '{ (args: Map[String, Any], _: Option[McpContext]) => args }

    // Handler typed at the method's required environment. Scala's normal type-checker enforces
    // that the surrounding `server.tool[R1 >: R](handler: ContextualToolHandler[R1])` constraint
    // is satisfied — but we've already verified that above with a friendlier message.
    val registration: Expr[ZIO[Any, Throwable, McpServerCore[R]]] = rMethodType match
      case '[rMethod] =>
        val handler: Expr[ContextualToolHandler[rMethod]] = effectShape match
          case MacroUtils.EffectShape.Pure =>
            '{ (args: Map[String, Any], ctxOpt: Option[McpContext]) =>
              ZIO.attempt {
                val patchedArgs = $withContext(args, ctxOpt)
                MapToFunctionMacro
                  .callByMap($methodRefExpr)
                  .asInstanceOf[Map[String, Any] => Any](patchedArgs)
              }
            }

          case MacroUtils.EffectShape.Zio =>
            '{ (args: Map[String, Any], ctxOpt: Option[McpContext]) =>
              ZIO.suspend {
                val patchedArgs = $withContext(args, ctxOpt)
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
                val patchedArgs = $withContext(args, ctxOpt)
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
                val patchedArgs = $withContext(args, ctxOpt)
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

  /** `Some(T)` when `param` is a repeated parameter `T*`. */
  private def repeatedElementType(using Quotes)(
      param: quotes.reflect.Symbol
  ): Option[quotes.reflect.TypeRepr] =
    import quotes.reflect.*
    param.termRef.widenTermRefByName match
      case AnnotatedType(underlying, annotation)
          if annotation.tpe.typeSymbol == defn.RepeatedAnnot =>
        underlying.typeArgs.headOption
      case _ => None

  private def optionStringExpr(using Quotes)(opt: Option[String]): Expr[Option[String]] =
    opt match
      case Some(s) => '{ Some(${ Expr(s) }) }
      case None => '{ None }

  private def optionBoolExpr(using Quotes)(opt: Option[Boolean]): Expr[Option[Boolean]] =
    opt match
      case Some(b) => '{ Some(${ Expr(b) }) }
      case None => '{ None }
