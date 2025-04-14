package fastmcp.macros

import scala.quoted.*
import scala.util.Try

/**
 * Utility methods shared between the processor objects (Compressed)
 */
private[macros] object MacroUtils:

  // Helper to parse Option[String] literals from annotation arguments
  private def parseOptionStringLiteral(argTermAny: Any)(using quotes: Quotes): Option[String] =
    import quotes.reflect.*
    val argTerm = argTermAny.asInstanceOf[Term]
    argTerm match {
      // Matches Some("literal") created via Some.apply[String]("literal")
      case Apply(TypeApply(Select(Ident("Some"), "apply"), _), List(Literal(StringConstant(s)))) => Some(s)
      // Matches Some("literal") created via Some("literal")
      case Apply(Select(Ident("Some"), "apply"), List(Literal(StringConstant(s)))) => Some(s)
      // Matches None
      case Select(Ident("None"), _) | Ident("None") => None
      case _ =>
        // report.warning(s"Could not parse Option[String] from term: ${argTerm.show}") // Optional warning
        None
    }

  // Gets a reference to the method within its owner object
  def getMethodRefExpr(ownerSymAny: Any, methodSymAny: Any)(using quotes: Quotes): Expr[Any] =
    import quotes.reflect.*
    val ownerSym = ownerSymAny.asInstanceOf[Symbol]
    val methodSym = methodSymAny.asInstanceOf[Symbol]

    val companionSym = ownerSym.companionModule
    val methodSymOpt = companionSym.declaredMethod(methodSym.name).headOption.getOrElse {
      report.errorAndAbort(
        s"Could not find method symbol for '${methodSym.name}' in ${companionSym.fullName}"
      )
    }
    Select(Ref(companionSym), methodSymOpt).etaExpand(Symbol.spliceOwner).asExprOf[Any]

  // Helper to parse @Tool annotation arguments
  def parseToolParams(termAny: Any)(using quotes: Quotes): (Option[String], Option[String], List[String]) =
    import quotes.reflect.*
    val term = termAny.asInstanceOf[Term]

    var toolName: Option[String] = None
    var toolDesc: Option[String] = None
    var toolTags: List[String] = Nil

    // Specific helper for List[String]
    def parseListString(argTerm: Term): List[String] = argTerm match {
      case Apply(TypeApply(Select(Ident("List"), "apply"), _), elems) =>
        elems.collect { case Literal(StringConstant(item)) => item }
      case Select(Ident("Nil"), _) | Apply(TypeApply(Select(Ident("List"), "apply"), _), Nil) => Nil
      case _ => Nil
    }

    term match {
      case Apply(Select(New(_), _), argTerms) =>
        argTerms.foreach {
          case NamedArg("name", valueTerm) => toolName = parseOptionStringLiteral(valueTerm)
          case NamedArg("description", valueTerm) => toolDesc = parseOptionStringLiteral(valueTerm)
          case NamedArg("tags", valueTerm) => toolTags = parseListString(valueTerm)
          case _ => () // Ignore other args
        }
      case _ => () // Ignore if not the expected Apply structure
    }
    (toolName, toolDesc, toolTags)

  // Helper to parse @Prompt annotation arguments
  def parsePromptParams(termAny: Any)(using quotes: Quotes): (Option[String], Option[String]) =
    import quotes.reflect.*
    val term = termAny.asInstanceOf[Term]

    var promptName: Option[String] = None
    var promptDesc: Option[String] = None

    term match {
      case Apply(Select(New(_), _), argTerms) =>
        argTerms.foreach {
          case NamedArg("name", valueTerm) => promptName = parseOptionStringLiteral(valueTerm)
          case NamedArg("description", valueTerm) => promptDesc = parseOptionStringLiteral(valueTerm)
          case _ => ()
        }
      case _ => ()
    }
    (promptName, promptDesc)

  // Helper to parse @PromptParam annotation arguments
  def parsePromptParamArgs(paramAnnotOptAny: Option[Any])(using quotes: Quotes): (Option[String], Boolean) =
    import quotes.reflect.*
    val paramAnnotOpt = paramAnnotOptAny.map(_.asInstanceOf[Term])

    paramAnnotOpt match {
      case Some(annotTerm) =>
        var paramDesc: Option[String] = None
        var paramRequired: Boolean = true // Default required for @PromptParam
        var descriptionSetPositionally = false
        var requiredSetByName = false

        annotTerm match {
          case Apply(_, args) =>
            args.foreach {
              // Positional description: Only take the first one encountered
              case Literal(StringConstant(s)) if paramDesc.isEmpty =>
                paramDesc = Some(s)
                descriptionSetPositionally = true
              // Named description
              case NamedArg("description", Literal(StringConstant(s))) =>
                paramDesc = Some(s)
              // Named required
              case NamedArg("required", Literal(BooleanConstant(b))) =>
                paramRequired = b
                requiredSetByName = true
              // Positional boolean: Only if description was set positionally and required wasn't set by name.
              case Literal(BooleanConstant(b)) if descriptionSetPositionally && !requiredSetByName =>
                paramRequired = b
              // Ignore other argument types or structures
              case _ => ()
            }
          case _ => () // Ignore if annotation term is not an Apply
        }
        (paramDesc, paramRequired)
      case None => (None, true) // Defaults if no @PromptParam
    }

  // Helper to parse @Resource annotation arguments
  def parseResourceParams(termAny: Any)(using quotes: Quotes): (String, Option[String], Option[String], Option[String]) =
    import quotes.reflect.*
    val term = termAny.asInstanceOf[Term]

    var uri: String = ""
    var resourceName: Option[String] = None
    var resourceDesc: Option[String] = None
    var mimeType: Option[String] = None

    term match {
      case Apply(Select(New(_), _), argTerms) =>
        argTerms.foreach {
          // Handle positional URI argument first
          case Literal(StringConstant(s)) if uri.isEmpty => uri = s
          case NamedArg("uri", Literal(StringConstant(s))) => uri = s
          case NamedArg("name", valueTerm) => resourceName = parseOptionStringLiteral(valueTerm)
          case NamedArg("description", valueTerm) => resourceDesc = parseOptionStringLiteral(valueTerm)
          case NamedArg("mimeType", valueTerm) => mimeType = parseOptionStringLiteral(valueTerm)
          case _ => ()
        }
      case _ => ()
    }
    if uri.isEmpty then report.errorAndAbort("@Resource annotation must have a 'uri' parameter.")
    (uri, resourceName, resourceDesc, mimeType)

  // Helper method to invoke a function (runtime)
  def invokeFunctionWithArgs(function: Any, args: List[Any]): Any = {
    val argCount = args.length
    // Direct invocation for known Function arities for performance and type safety
    function match {
      case f: Function0[?] if argCount == 0 => f()
      case f: Function1[?, ?] if argCount == 1 => f.asInstanceOf[Function1[Any, Any]](args.head)
      case f: Function2[?, ?, ?] if argCount == 2 => f.asInstanceOf[Function2[Any, Any, Any]](args(0), args(1))
      case f: Function3[?, ?, ?, ?] if argCount == 3 => f.asInstanceOf[Function3[Any, Any, Any, Any]](args(0), args(1), args(2))
      case f: Function4[?, ?, ?, ?, ?] if argCount == 4 => f.asInstanceOf[Function4[Any, Any, Any, Any, Any]](args(0), args(1), args(2), args(3))
      case f: Function5[?, ?, ?, ?, ?, ?] if argCount == 5 => f.asInstanceOf[Function5[Any, Any, Any, Any, Any, Any]](args(0), args(1), args(2), args(3), args(4))
      case f: Function6[?, ?, ?, ?, ?, ?, ?] if argCount == 6 => f.asInstanceOf[Function6[Any, Any, Any, Any, Any, Any, Any]](args(0), args(1), args(2), args(3), args(4), args(5))
      case f: Function7[?, ?, ?, ?, ?, ?, ?, ?] if argCount == 7 => f.asInstanceOf[Function7[Any, Any, Any, Any, Any, Any, Any, Any]](args(0), args(1), args(2), args(3), args(4), args(5), args(6))
      case f: Function8[?, ?, ?, ?, ?, ?, ?, ?, ?] if argCount == 8 => f.asInstanceOf[Function8[Any, Any, Any, Any, Any, Any, Any, Any, Any]](args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7))
      case f: Function9[?, ?, ?, ?, ?, ?, ?, ?, ?, ?] if argCount == 9 => f.asInstanceOf[Function9[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]](args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7), args(8))
      case f: Function10[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] if argCount == 10 => f.asInstanceOf[Function10[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]](args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7), args(8), args(9))
      case f: Function11[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] if argCount == 11 => f.asInstanceOf[Function11[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]](args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7), args(8), args(9), args(10))
      case f: Function12[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] if argCount == 12 => f.asInstanceOf[Function12[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]](args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7), args(8), args(9), args(10), args(11))
      case f: Function13[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] if argCount == 13 => f.asInstanceOf[Function13[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]](args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7), args(8), args(9), args(10), args(11), args(12))
      case f: Function14[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] if argCount == 14 => f.asInstanceOf[Function14[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]](args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7), args(8), args(9), args(10), args(11), args(12), args(13))
      case f: Function15[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] if argCount == 15 => f.asInstanceOf[Function15[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]](args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7), args(8), args(9), args(10), args(11), args(12), args(13), args(14))
      case f: Function16[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] if argCount == 16 => f.asInstanceOf[Function16[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]](args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7), args(8), args(9), args(10), args(11), args(12), args(13), args(14), args(15))
      case f: Function17[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] if argCount == 17 => f.asInstanceOf[Function17[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]](args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7), args(8), args(9), args(10), args(11), args(12), args(13), args(14), args(15), args(16))
      case f: Function18[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] if argCount == 18 => f.asInstanceOf[Function18[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]](args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7), args(8), args(9), args(10), args(11), args(12), args(13), args(14), args(15), args(16), args(17))
      case f: Function19[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] if argCount == 19 => f.asInstanceOf[Function19[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]](args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7), args(8), args(9), args(10), args(11), args(12), args(13), args(14), args(15), args(16), args(17), args(18))
      case f: Function20[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] if argCount == 20 => f.asInstanceOf[Function20[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]].apply(args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7), args(8), args(9), args(10), args(11), args(12), args(13), args(14), args(15), args(16), args(17), args(18), args(19))
      case f: Function21[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] if argCount == 21 => f.asInstanceOf[Function21[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]].apply(args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7), args(8), args(9), args(10), args(11), args(12), args(13), args(14), args(15), args(16), args(17), args(18), args(19), args(20))
      case f: Function22[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] if argCount == 22 => f.asInstanceOf[Function22[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]].apply(args(0), args(1), args(2), args(3), args(4), args(5), args(6), args(7), args(8), args(9), args(10), args(11), args(12), args(13), args(14), args(15), args(16), args(17), args(18), args(19), args(20), args(21))
      // Reflection fallback for other cases or non-Function types with matching arity 'apply'
      case _ =>
        Try {
          // Find a method named "apply" or any method with the correct parameter count.
          // This is a basic fallback and might not find the intended method if overloaded.
          val methodToInvoke = function.getClass.getMethods.find { m =>
            m.getParameterCount == argCount && (m.getName == "apply" || m.getReturnType != Void.TYPE)
          }.getOrElse {
            throw new NoSuchMethodException(s"Suitable method with $argCount parameters not found on ${function.getClass.getName}")
          }
          // Prepare arguments for reflection call
          val invokeArgs = args.map(_.asInstanceOf[Object]).toArray
          methodToInvoke.invoke(function, invokeArgs*)
        }.recover {
          case e: Exception =>
            System.err.println(s"Reflection invocation failed for ${function.getClass.getName} with $argCount args: ${e.getMessage}")
            throw new RuntimeException(s"Failed to invoke function via reflection: ${e.getMessage}", e)
        }.get // Re-throw exception if recovery failed
    }
  }
end MacroUtils
