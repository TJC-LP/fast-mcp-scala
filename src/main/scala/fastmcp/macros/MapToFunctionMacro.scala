package fastmcp.macros

import com.fasterxml.jackson.databind.DeserializationFeature

import scala.annotation.tailrec
import scala.quoted.*
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.scala.{ClassTagExtensions, DefaultScalaModule}

import scala.reflect.ClassTag

object MapToFunctionMacro:

  // Jackson JsonMapper instance
  // CORRECTED initialization using 'with' for mixin
  private val mapperBuilder = JsonMapper.builder() // Start with JsonMapper builder
    .addModule(DefaultScalaModule) // Add Scala module
    .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)

  private val mapper: JsonMapper & ClassTagExtensions = // Type uses '&'
    mapperBuilder
      .build() // Build the JsonMapper
      :: ClassTagExtensions

  /**
   * Given a function `f`, returns a new function: `Map[String, Any] => R`,
   * where R is the return type of the original function.
   * The returned function will look up each parameter by name in the `Map`,
   * use Jackson to convert it to the correct type, and invoke `f` with those arguments.
   *
   * Example:
   * {{{
   *   def createUser(name: String, age: Int) = s"User($name, $age)"
   *   val fn = callByMap(createUser _) // fn: Map[String, Any] => String
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
          report.warning(s"Encountered PolyType ${pt.show}. Applying 'Any' which might lead to runtime issues if type parameters are needed for conversion.")
          val dummyAppliedTerm = term.appliedToTypes(pt.paramNames.map(_ => TypeRepr.of[Any]))
          extractParamsAndReturnType(dummyAppliedTerm) // Recurse with applied types

        case atpe@AppliedType(base, args) if base.typeSymbol.fullName.startsWith("scala.Function") =>
          val paramTypes = args.init // Last type is return type
          val returnType = args.last
          // Generate fallback names initially
          val params = paramTypes.zipWithIndex.map { case (tpe, i) =>
            ParamInfo(s"arg$i", tpe)
          }.toList
          (params, returnType)

        case _ =>
          report.errorAndAbort(s"Couldn't extract parameters from function: ${term.show}, type: ${tpe.show}")

    // Try to extract real parameter names from method references
    @tailrec
    def tryGetRealParamNames(term: Term): Option[List[String]] = term match
      case Inlined(_, _, inner) => tryGetRealParamNames(inner)
      case Block(_, expr) => tryGetRealParamNames(expr)
      case ident@Ident(_) if ident.symbol.isDefDef && !ident.symbol.flags.is(Flags.Synthetic) =>
        ident.symbol.paramSymss.headOption.map(_.map(_.name))
      case select@Select(_, _) if select.symbol.isDefDef && !select.symbol.flags.is(Flags.Synthetic) =>
        select.symbol.paramSymss.headOption.map(_.map(_.name))
      case closure@Closure(meth@Ident(_), _) if meth.symbol.isDefDef =>
        meth.symbol.paramSymss.headOption.map(_.map(_.name))
      // This case intentionally removed as it's unreachable in our code structure
      case _ => None

    // Helper to summon ClassTag Expr or abort
    def summonClassTagExprFor(tpe: TypeRepr): Expr[ClassTag[?]] = // Returns Expr[ClassTag[?]]
      tpe.asType match
        case '[t] =>
          Expr.summon[ClassTag[t]] match
            case Some(ct) => ct // ct has type Expr[ClassTag[t]] here
            case None => report.errorAndAbort(s"Cannot summon ClassTag for type ${tpe.show}. Ensure a ClassTag is available.")
        // The overall return type gets widened to Expr[ClassTag[?]]
        case _ => report.errorAndAbort(s"Could not get Type from TypeRepr: ${tpe.show}")

    // Helper to generate Expr[List[Any]] for argument conversion
    def buildArgConversionExpr(params: List[ParamInfo], mapExpr: Expr[Map[String, Any]])(using Quotes): Expr[List[Any]] = {
      val conversionExprs: List[Expr[Any]] = params.map { p =>
        val ctExpr: Expr[ClassTag[?]] = summonClassTagExprFor(p.tpe) // Gets Expr[ClassTag[?]]
        val nameExpr = Expr(p.name)
        val typeStr = Expr(p.tpe.show)
        
        p.tpe.asType match {
          // List[t] handling
          case '[List[t]] =>
            val elemType = p.tpe.typeArgs.head
            val elemTypeStr = Expr(elemType.show)
            val elemClassTagExpr = summonClassTagExprFor(elemType)
            '{
              val nameStr = $nameExpr
              val mapValue = $mapExpr.getOrElse(nameStr, throw new NoSuchElementException(s"Key not found in map: $nameStr"))
              if (mapValue == null) {
                throw new RuntimeException(s"Null value provided for non-optional parameter '$nameStr' of type List[${$elemTypeStr}]")
              }
              // Implicit ClassTag for the element type 't' needed by Jackson's convertValue for collections
              implicit val elemCt: ClassTag[t] = ${ elemClassTagExpr.asInstanceOf[Expr[ClassTag[t]]] }
              // Implicit ClassTag for the overall List[t] might also be needed by some Jackson internals
              implicit val listCt: ClassTag[List[t]] = ${ ctExpr.asInstanceOf[Expr[ClassTag[List[t]]]] }
              try {
                MapToFunctionMacro.mapper.convertValue[List[t]](mapValue)
              } catch {
                case e: Exception =>
                  throw new RuntimeException(s"Failed to convert value for parameter '$nameStr' to type List[${$elemTypeStr}]. Value: $mapValue", e)
              }
            }.asExprOf[Any]
          // Vector[t] handling
          case '[Vector[t]] =>
            val elemType = p.tpe.typeArgs.head
            val elemTypeStr = Expr(elemType.show)
            val elemClassTagExpr = summonClassTagExprFor(elemType)
            '{
              val nameStr = $nameExpr
              val mapValue = $mapExpr.getOrElse(nameStr, throw new NoSuchElementException(s"Key not found in map: $nameStr"))
              if (mapValue == null) {
                throw new RuntimeException(s"Null value provided for non-optional parameter '$nameStr' of type Vector[${$elemTypeStr}]")
              }
              implicit val elemCt: ClassTag[t] = ${ elemClassTagExpr.asInstanceOf[Expr[ClassTag[t]]] }
              implicit val vecCt: ClassTag[Vector[t]] = ${ ctExpr.asInstanceOf[Expr[ClassTag[Vector[t]]]] }
              try {
                MapToFunctionMacro.mapper.convertValue[Vector[t]](mapValue)
              } catch {
                case e: Exception =>
                  throw new RuntimeException(s"Failed to convert value for parameter '$nameStr' to type Vector[${$elemTypeStr}]. Value: $mapValue", e)
              }
            }.asExprOf[Any]
          // Set[t] handling
          case '[Set[t]] =>
            val elemType = p.tpe.typeArgs.head
            val elemTypeStr = Expr(elemType.show)
            val elemClassTagExpr = summonClassTagExprFor(elemType)
            '{
              val nameStr = $nameExpr
              val mapValue = $mapExpr.getOrElse(nameStr, throw new NoSuchElementException(s"Key not found in map: $nameStr"))
              if (mapValue == null) {
                throw new RuntimeException(s"Null value provided for non-optional parameter '$nameStr' of type Set[${$elemTypeStr}]")
              }
              implicit val elemCt: ClassTag[t] = ${ elemClassTagExpr.asInstanceOf[Expr[ClassTag[t]]] }
              implicit val setCt: ClassTag[Set[t]] = ${ ctExpr.asInstanceOf[Expr[ClassTag[Set[t]]]] }
              try {
                MapToFunctionMacro.mapper.convertValue[Set[t]](mapValue)
              } catch {
                case e: Exception =>
                  throw new RuntimeException(s"Failed to convert value for parameter '$nameStr' to type Set[${$elemTypeStr}]. Value: $mapValue", e)
              }
            }.asExprOf[Any]
          // Map[k, v] handling - Enforce String keys as Jackson typically expects this from JSON-like maps
          case '[Map[k, v]] =>
            if (p.tpe.typeArgs.length != 2) { // Sanity check
              report.errorAndAbort(s"Expected 2 type arguments for Map parameter '${p.name}', found ${p.tpe.typeArgs.length}")
            }
            val keyType = p.tpe.typeArgs(0)
            val valueType = p.tpe.typeArgs(1)

            // Jackson generally requires String keys when converting from general Maps/JSON objects
            keyType.asType match {
              case '[String] => // Key is String, proceed
                val valueTypeStr = Expr(valueType.show)
                val valueClassTagExpr = summonClassTagExprFor(valueType)
                '{
                  val nameStr = $nameExpr
                  val mapValue = $mapExpr.getOrElse(nameStr, throw new NoSuchElementException(s"Key not found in map: $nameStr"))
                  if (mapValue == null) {
                    throw new RuntimeException(s"Null value provided for non-optional parameter '$nameStr' of type Map[String, ${$valueTypeStr}]")
                  }
                  // Need ClassTag for the value type 'v'
                  implicit val valCt: ClassTag[v] = ${ valueClassTagExpr.asInstanceOf[Expr[ClassTag[v]]] }
                  // Implicit ClassTag for the overall Map type
                  implicit val mapCt: ClassTag[Map[String, v]] = ${ ctExpr.asInstanceOf[Expr[ClassTag[Map[String, v]]]] }
                  try {
                    // Note: k is known to be String here due to the outer match
                    MapToFunctionMacro.mapper.convertValue[Map[String, v]](mapValue)
                  } catch {
                    case e: Exception =>
                      throw new RuntimeException(s"Failed to convert value for parameter '$nameStr' to type Map[String, ${$valueTypeStr}]. Value: $mapValue", e)
                  }
                }.asExprOf[Any]
              case _ =>
                report.errorAndAbort(s"Parameter '${p.name}' has type Map[${keyType.show}, ?]. Only Map[String, ?] is supported for conversion.")
            }
          case '[Option[t]] =>
            val innerTypeStr = Expr(p.tpe.typeArgs.head.show)
            val innerClassTagExpr = summonClassTagExprFor(p.tpe.typeArgs.head)
            '{
              val nameStr = $nameExpr
              if ($mapExpr.contains(nameStr)) {
                val rawValue = $mapExpr(nameStr)
                if (rawValue == null || rawValue == None) None
                else {
                  implicit val ct: ClassTag[t] = ${ innerClassTagExpr.asInstanceOf[Expr[ClassTag[t]]] }
                  rawValue match {
                    case Some(v) => Some(MapToFunctionMacro.mapper.convertValue[t](v))
                    case v => Some(MapToFunctionMacro.mapper.convertValue[t](v))
                  }
                }
              } else None
            }
          case '[t] => // Specific type 't' is known here
            // Determine if the type 't' is a specific generic instance (like Option[String])
            // by checking if its TypeRepr has type arguments.
            val isGenericInstance = p.tpe.typeArgs.nonEmpty

            // Expr[t] representing the code to convert the value at runtime
            val conversionExpr = '{
              val nameStr = $nameExpr
              val mapValue = $mapExpr.getOrElse(nameStr, throw new NoSuchElementException(s"Key not found in map: $nameStr"))

              implicit val ct: ClassTag[t] = ${ ctExpr.asInstanceOf[Expr[ClassTag[t]]] }
              // Use Jackson to convert
              try {
                MapToFunctionMacro.mapper.convertValue[t](mapValue)
              } catch {
                case e: Exception =>
                  // Add more specific exception handling if needed (e.g., JsonProcessingException)
                  throw new RuntimeException(s"Failed to convert generic=${${ Expr(isGenericInstance) }} value for parameter '$nameStr' to type ${$typeStr}. Value: $mapValue", e)
              }
            }
            conversionExpr.asExprOf[Any] // Cast Expr[t] to Expr[Any] for the list
        }
      }
      Expr.ofList(conversionExprs) // Create Expr[List[Any]]
    }


    // --- Main Logic ---
    val fnTerm = f.asTerm
    val (params, returnTypeRepr) = extractParamsAndReturnType(fnTerm)

    // Try to get real parameter names and update ParamInfo
    val realNames = tryGetRealParamNames(fnTerm)
    val namedParams = realNames match
      case Some(names) if names.length == params.length =>
        params.zip(names).map { case (param, name) => param.copy(name = name) }
      case Some(names) =>
        report.warning(s"Parameter name count mismatch. Expected ${params.length}, got ${names.length}. Using fallback names.")
        params // Use fallback names
      case None =>
        report.warning(s"Could not resolve parameter names for ${fnTerm.show}. Using fallback names.")
        params // Use fallback names

    // Generate the lambda expression
    returnTypeRepr.asType match
      case '[returnType] => // The actual return type R of the original function
        '{
          // This is the function Map[String, Any] => returnType that the macro generates
          (map: Map[String, Any]) =>
            val fnValue = $f // The original function value captured

            // Generate the list of converted arguments at runtime using Jackson
            val argsList: List[Any] = ${ buildArgConversionExpr(namedParams, 'map) }

            // Invoke the original function using the runtime reflection helper
            val result = MacroUtils.invokeFunctionWithArgs(fnValue, argsList)

            // Cast the result to the correct return type
            result.asInstanceOf[returnType]

        }.asExprOf[Map[String, Any] => returnType] // Ensure the overall expression has the function type

      case _ => report.errorAndAbort(s"Could not resolve return type: ${returnTypeRepr.show}")
