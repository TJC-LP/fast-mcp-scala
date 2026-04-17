package com.tjclp.fastmcp
package macros

import scala.quoted.*

import com.tjclp.fastmcp.server.JsMcpServer

/** JS-target helper trait mirroring the JVM annotation processors but specialized to
  * [[JsMcpServer]].
  */
private[macros] trait AnnotationProcessorBase:

  protected inline def findAnnotation[A: Type](using Quotes)(
      sym: quotes.reflect.Symbol
  ): Option[quotes.reflect.Term] =
    MacroUtils.extractAnnotation[A](sym)

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

  protected def methodRef(using Quotes)(
      owner: quotes.reflect.Symbol,
      method: quotes.reflect.Symbol
  ): Expr[Any] =
    MacroUtils.getMethodRefExpr(owner, method)

  protected def runAndReturnServer(
      server: Expr[JsMcpServer]
  )(registration: Expr[Any])(using Quotes): Expr[JsMcpServer] =
    '{
      import zio.*
      Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe
          .run($registration.asInstanceOf[zio.ZIO[Any, Throwable, Any]])
          .getOrThrowFiberFailure()
      }
      $server
    }
