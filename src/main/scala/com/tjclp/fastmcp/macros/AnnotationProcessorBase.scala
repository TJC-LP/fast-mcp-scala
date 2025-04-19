package com.tjclp.fastmcp
package macros

import com.tjclp.fastmcp.server.*

import scala.quoted.*

/** Helper trait that captures the common boiler‑plate shared by the three annotation processors
  * (Tool / Prompt / Resource).
  *
  * • Extraction helpers – locate the annotation instance, resolve `name` / `description` with a
  * Scaladoc fallback, etc. • Method reference helper – produces a stable `Expr[Any]` pointing at
  * the annotated method so we can call it through `MapToFunctionMacro` later. •
  * `runAndReturnServer` – executes the chosen `FastMcpServer` registration effect immediately (so
  * that compile‑time evaluation fails fast) and then returns the untouched server value so that
  * macro callers can simply inline the resulting expression.
  */
private[macros] trait AnnotationProcessorBase:

  // ---------------------------------------------------------------------------
  // Generic helpers shared by all annotation processors
  // ---------------------------------------------------------------------------

  /** Fetch the first annotation of the requested type that exists on the supplied Symbol. */
  protected inline def findAnnotation[A: Type](using Quotes)(
      sym: quotes.reflect.Symbol
  ): Option[quotes.reflect.Term] =
    MacroUtils.extractAnnotation[A](sym)

  /** Retrieves `(name, description)` information from any annotation that follows the `(name:
    * Option[String], description: Option[String], ...)` constructor pattern.
    *
    * If no description is provided, we fall back to the method's Scaladoc instead – this lets users
    * document their APIs in one place.
    */
  protected def nameAndDescription(using Quotes)(
      annot: quotes.reflect.Term,
      methodSym: quotes.reflect.Symbol
  ): (String, Option[String]) =
    import quotes.reflect.*

    val (maybeName, maybeDesc) = annot match
      case Apply(_, args) =>
        val literals = args.collect {
          case Literal(StringConstant(s)) => Some(s)
          case Apply(_, List(Literal(StringConstant(s)))) => Some(s) // Some("foo")
          case NamedArg(_, Literal(StringConstant(s))) => Some(s)
          case NamedArg(_, Apply(_, List(Literal(StringConstant(s))))) =>
            Some(s) // Some("foo") with named arg
          case _ => None
        }.flatten
        (literals.headOption, literals.drop(1).headOption)
      case _ => (None, None)

    val name = maybeName.getOrElse(methodSym.name)
    val desc0 = maybeDesc

    (name, desc0.orElse(methodSym.docstring))

  /** Build a stable method reference expression that survives inlining and can be fed into
    * MapToFunctionMacro.
    */
  protected def methodRef(using Quotes)(
      owner: quotes.reflect.Symbol,
      method: quotes.reflect.Symbol
  ): Expr[Any] =
    MacroUtils.getMethodRefExpr(owner, method)

  /** Executes the given registration effect eagerly inside the default ZIO runtime and then returns
    * the *original* server value so callers can simply return this expression from the macro
    * expansion.
    */
  protected def runAndReturnServer(
      server: Expr[FastMcpServer]
  )(registration: Expr[Any])(using Quotes): Expr[FastMcpServer] =
    '{
      import zio.*
      Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe
          .run($registration.asInstanceOf[zio.ZIO[Any, Throwable, Any]])
          .getOrThrowFiberFailure()
      }
      $server
    }
