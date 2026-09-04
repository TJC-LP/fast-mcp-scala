package com.tjclp.fastmcp
package macros

import scala.quoted.*

import com.tjclp.fastmcp.server.McpServerCore

/** Helper trait capturing the common boilerplate shared by the three annotation processors (Tool /
  * Prompt / Resource). Generated registration code targets the abstract `McpServerCore[R]` trait so
  * a single shared implementation works on every backend.
  */
private[macros] trait AnnotationProcessorBase:

  /** Fetch the first annotation of the requested type on the supplied Symbol. */
  protected inline def findAnnotation[A: Type](using Quotes)(
      sym: quotes.reflect.Symbol
  ): Option[quotes.reflect.Term] =
    MacroUtils.extractAnnotation[A](sym)

  /** Retrieve `(name, description)` from an annotation whose constructor starts with `(name:
    * Option[String], description: Option[String], ...)` (`@Tool`, `@Prompt`).
    *
    * Typed annotation trees are argument-complete and in constructor order: named arguments stay
    * `NamedArg`, omitted ones appear as `<Annot>.$lessinit$greater$default$N`. So `name` is either
    * `NamedArg("name", v)` or the unnamed argument at index 0, and `description` is
    * `NamedArg("description", v)` or the unnamed argument at index 1; nothing else can ever be
    * taken as the registered name (a description-only annotation registers under the method name).
    * A present but non-literal `name` / `description` is a compile-time error (see
    * [[MacroUtils.parseOptionStringLiteral]]) rather than a silent fallback. Falls back to the
    * method name and its Scaladoc.
    */
  protected def nameAndDescription(using Quotes)(
      annot: quotes.reflect.Term,
      methodSym: quotes.reflect.Symbol
  ): (String, Option[String]) =
    import quotes.reflect.*

    val annotName = "@" + annot.tpe.typeSymbol.name
    def parseName(v: Term) = MacroUtils.parseOptionStringLiteral(v, s"$annotName(name)")
    def parseDesc(v: Term) = MacroUtils.parseOptionStringLiteral(v, s"$annotName(description)")

    val (maybeName, maybeDesc) = annot match
      case Apply(_, args) =>
        args.zipWithIndex.foldLeft((Option.empty[String], Option.empty[String])) {
          case ((_, d), (NamedArg("name", v), _)) => (parseName(v), d)
          case ((n, _), (NamedArg("description", v), _)) => (n, parseDesc(v))
          case (acc, (NamedArg(_, _), _)) => acc
          case ((_, d), (v, 0)) => (parseName(v), d)
          case ((n, _), (v, 1)) => (n, parseDesc(v))
          case (acc, _) => acc
        }
      case _ => (None, None)

    (maybeName.getOrElse(methodSym.name), maybeDesc.orElse(methodSym.docstring))

  /** Build a method reference expression that survives inlining and denotes EXACTLY `method` (the
    * annotated symbol), never a same-named sibling overload — see [[MacroUtils.getMethodRefExpr]].
    */
  protected def methodRef(using Quotes)(
      owner: quotes.reflect.Symbol,
      method: quotes.reflect.Symbol
  ): Expr[Any] =
    MacroUtils.getMethodRefExpr(owner, method)

  /** Execute the registration effect eagerly inside the default ZIO runtime, returning the server
    * value so callers can inline the expression directly.
    *
    * Registration is environment-free (`ZIO[Any, Throwable, ?]`) even when the *handlers* require
    * an `R` — handler effects only run later, on the server's `executionRuntime` captured at
    * `runHttp[R]()` / `runStdio[R]()` entry.
    */
  protected def runAndReturnServer[R: Type](
      server: Expr[McpServerCore[R]]
  )(registration: Expr[Any])(using Quotes): Expr[McpServerCore[R]] =
    '{
      import zio.*
      Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe
          .run($registration.asInstanceOf[zio.ZIO[Any, Throwable, Any]])
          .getOrThrowFiberFailure()
      }
      $server
    }
