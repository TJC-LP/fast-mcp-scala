package com.tjclp.fastmcp
package macros

import scala.quoted.*

import com.tjclp.fastmcp.server.McpServerCore

/** Helper trait capturing the common boilerplate shared by the three annotation processors (Tool /
  * Prompt / Resource). Generated registration code targets the abstract `McpServerCore` trait so a
  * single shared implementation works on every backend.
  */
private[macros] trait AnnotationProcessorBase:

  /** Fetch the first annotation of the requested type on the supplied Symbol. */
  protected inline def findAnnotation[A: Type](using Quotes)(
      sym: quotes.reflect.Symbol
  ): Option[quotes.reflect.Term] =
    MacroUtils.extractAnnotation[A](sym)

  /** Retrieve `(name, description)` from any annotation following the `(name: Option[String],
    * description: Option[String], ...)` constructor pattern. Falls back to the method's Scaladoc.
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
          case Apply(_, List(Literal(StringConstant(s)))) => Some(s)
          case NamedArg(_, Literal(StringConstant(s))) => Some(s)
          case NamedArg(_, Apply(_, List(Literal(StringConstant(s))))) => Some(s)
          case _ => None
        }.flatten
        (literals.headOption, literals.drop(1).headOption)
      case _ => (None, None)

    val name = maybeName.getOrElse(methodSym.name)
    (name, maybeDesc.orElse(methodSym.docstring))

  /** Build a stable method reference expression that survives inlining. */
  protected def methodRef(using Quotes)(
      owner: quotes.reflect.Symbol,
      method: quotes.reflect.Symbol
  ): Expr[Any] =
    MacroUtils.getMethodRefExpr(owner, method)

  /** Execute the registration effect eagerly inside the default ZIO runtime, returning the server
    * value so callers can inline the expression directly.
    */
  protected def runAndReturnServer(
      server: Expr[McpServerCore]
  )(registration: Expr[Any])(using Quotes): Expr[McpServerCore] =
    '{
      import zio.*
      Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe
          .run($registration.asInstanceOf[zio.ZIO[Any, Throwable, Any]])
          .getOrThrowFiberFailure()
      }
      $server
    }
