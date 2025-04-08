package fastmcp.macros

import scala.quoted.*
import scala.annotation.tailrec
import java.lang.reflect.Method

object MapToFunctionMacro:

  /**
   * Given a function `f`, returns a new function: `Map[String, Any] => R`,
   * where R is the return type of the original function.
   * The returned function will look up each parameter by name in the `Map`,
   * cast it to the correct type, and invoke `f` with those arguments.
   *
   * Example:
   * {{{
   *   def createUser(name: String, age: Int) = s"User($name, $age)"
   *   val fn = callByMap(createUser) // fn: Map[String, Any] => String
   *   println(fn(Map("name" -> "Alice", "age" -> 42))) // => "User(Alice, 42)"
   * }}}
   */
  transparent inline def callByMap[F](inline f: F): Any =
    ${ callByMapImpl('f) }

  private def callByMapImpl[F: Type](f: Expr[F])(using q: Quotes): Expr[Any] =
    import q.reflect.*

    // Helper class to store parameter information
    case class ParamInfo(name: String, tpe: TypeRepr)

    // Extract the parameters and return type from the function
    @tailrec
    def extractParamsAndReturnType(term: Term): (List[ParamInfo], TypeRepr) =
      val tpe = term.tpe.widen

      tpe match
        case mt: MethodType =>
          val params = mt.paramNames.zip(mt.paramTypes).map {
            case (name, tpe) => ParamInfo(name, tpe)
          }
          (params, mt.resType)

        case pt: PolyType =>
          extractParamsAndReturnType(term.appliedToTypes(pt.paramNames.map(_ => TypeRepr.of[Any])))

        case atpe @ AppliedType(base, args) if base.typeSymbol.fullName.startsWith("scala.Function") =>
          val paramTypes = args.init // Last type is return type
          val returnType = args.last
          val params = paramTypes.indices.map(i =>
            ParamInfo(s"arg$i", paramTypes(i))
          ).toList
          (params, returnType)

        case _ =>
          report.errorAndAbort(s"Couldn't extract parameters from function: ${term.show}, type: ${tpe.show}")

    // Try to extract real parameter names from method references
    @tailrec
    def tryGetRealParamNames(term: Term): Option[List[String]] = term match
      case Inlined(_, _, inner) => tryGetRealParamNames(inner)
      case Block(_, expr)      => tryGetRealParamNames(expr)
      case ident @ Ident(_) if ident.symbol.isDefDef =>
        ident.symbol.paramSymss.headOption.map(_.map(_.name))
      case select @ Select(_, _) if select.symbol.isDefDef =>
        select.symbol.paramSymss.headOption.map(_.map(_.name))
      case closure @ Closure(methRef, _) if methRef.symbol.isDefDef =>
        methRef.symbol.paramSymss.headOption.map(_.map(_.name))
      case _ => None

    // Extract parameters and return type
    val (params, returnTypeRepr) = extractParamsAndReturnType(f.asTerm)

    // Try to get real parameter names
    val realNames = tryGetRealParamNames(f.asTerm)
    val namedParams = realNames match
      case Some(names) if names.length == params.length =>
        names.zip(params).map { case (name, param) => param.copy(name = name) }
      case _ => params

    // Create a lambda expression with the correct return type
    returnTypeRepr.asType match
      case '[returnType] =>
        '{
          (map: Map[String, Any]) =>
            // Store the function value
            val fnValue = $f

            // Capture the parameter info at runtime
            val paramInfo: List[(String, String)] = ${Expr(namedParams.map(p => (p.name, p.tpe.show)))}

            // Get the runtime arguments from the map
            val args = paramInfo.map { case (name, tpeStr) =>
              map.get(name) match
                case Some(value) =>
                  try value
                  catch
                    case e: ClassCastException =>
                      throw new RuntimeException("Parameter '" + name + "' has incorrect type. Expected: " + tpeStr + ", got: " + value.getClass.getName, e)
                case None =>
                  throw new RuntimeException("Missing required parameter: '" + name + "'")
            }

            // Use reflection to invoke the function with arbitrary number of arguments
            val result = invokeFunctionWithArgs(fnValue, args)
            result.asInstanceOf[returnType]
        }.asExprOf[Map[String, Any] => returnType]

  // Helper method to invoke a function with a list of arguments using reflection
  private def invokeFunctionWithArgs(function: Any, args: List[Any]): Any =
    function match
      // Handle standard Scala functions
      case f: Function0[?] => f.apply()
      case f: Function1[?, ?] => f.asInstanceOf[Function1[Any, Any]].apply(args(0))
      case f: Function2[?, ?, ?] => f.asInstanceOf[Function2[Any, Any, Any]].apply(args(0), args(1))
      case f: Function3[?, ?, ?, ?] => f.asInstanceOf[Function3[Any, Any, Any, Any]].apply(args(0), args(1), args(2))
      case f: Function4[?, ?, ?, ?, ?] => f.asInstanceOf[Function4[Any, Any, Any, Any, Any]].apply(args(0), args(1), args(2), args(3))
      case f: Function5[?, ?, ?, ?, ?, ?] => f.asInstanceOf[Function5[Any, Any, Any, Any, Any, Any]].apply(args(0), args(1), args(2), args(3), args(4))
      case f: Function6[?, ?, ?, ?, ?, ?, ?] => f.asInstanceOf[Function6[Any, Any, Any, Any, Any, Any, Any]].apply(args(0), args(1), args(2), args(3), args(4), args(5))
      case f: Function7[?, ?, ?, ?, ?, ?, ?, ?] => f.asInstanceOf[Function7[Any, Any, Any, Any, Any, Any, Any, Any]].apply(args(0), args(1), args(2), args(3), args(4), args(5), args(6))
      case f: Function8[?, ?, ?, ?, ?, ?, ?, ?, ?] => f.asInstanceOf[Function8[Any, Any, Any, Any, Any, Any, Any, Any, Any]].apply(args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7))
      case f: Function9[?, ?, ?, ?, ?, ?, ?, ?, ?, ?] => f.asInstanceOf[Function9[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]].apply(args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7), args(8))
      case f: Function10[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] => f.asInstanceOf[Function10[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]].apply(args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7), args(8), args(9))
      case f: Function11[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] => f.asInstanceOf[Function11[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]].apply(args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7), args(8), args(9), args(10))
      case f: Function12[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] => f.asInstanceOf[Function12[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]].apply(args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7), args(8), args(9), args(10), args(11))
      case f: Function13[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] => f.asInstanceOf[Function13[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]].apply(args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7), args(8), args(9), args(10), args(11), args(12))
      case f: Function14[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] => f.asInstanceOf[Function14[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]].apply(args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7), args(8), args(9), args(10), args(11), args(12), args(13))
      case f: Function15[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] => f.asInstanceOf[Function15[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]].apply(args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7), args(8), args(9), args(10), args(11), args(12), args(13), args(14))
      case f: Function16[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] => f.asInstanceOf[Function16[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]].apply(args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7), args(8), args(9), args(10), args(11), args(12), args(13), args(14), args(15))
      case f: Function17[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] => f.asInstanceOf[Function17[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]].apply(args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7), args(8), args(9), args(10), args(11), args(12), args(13), args(14), args(15), args(16))
      case f: Function18[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] => f.asInstanceOf[Function18[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]].apply(args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7), args(8), args(9), args(10), args(11), args(12), args(13), args(14), args(15), args(16), args(17))
      case f: Function19[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] => f.asInstanceOf[Function19[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]].apply(args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7), args(8), args(9), args(10), args(11), args(12), args(13), args(14), args(15), args(16), args(17), args(18))
      case f: Function20[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] => f.asInstanceOf[Function20[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]].apply(args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7), args(8), args(9), args(10), args(11), args(12), args(13), args(14), args(15), args(16), args(17), args(18), args(19))
      case f: Function21[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] => f.asInstanceOf[Function21[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]].apply(args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7), args(8), args(9), args(10), args(11), args(12), args(13), args(14), args(15), args(16), args(17), args(18), args(19), args(20))
      case f: Function22[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] => f.asInstanceOf[Function22[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]].apply(args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7), args(8), args(9), args(10), args(11), args(12), args(13), args(14), args(15), args(16), args(17), args(18), args(19), args(20), args(21))

      // For methods with more than 22 parameters or other types, use Java reflection
      case _ =>
        try
          // Convert to method reference if needed
          val methodObj = function.getClass.getMethods.find(_.getName == "apply").getOrElse {
            throw new RuntimeException("Could not find method to invoke on " + function.getClass.getName)
          }
          // Invoke the method with the arguments
          methodObj.invoke(function, args.map(_.asInstanceOf[Object]).toArray*)
        catch
          case e: Exception =>
            throw new RuntimeException("Failed to invoke function: " + e.getMessage, e)
