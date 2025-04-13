package fastmcp.macros

import scala.quoted.*

/**
 * Utility methods shared between the processor objects
 */
private[macros] object MacroUtils:
  // Gets a reference to the method within its owner object
  def getMethodRefExpr(ownerSymAny: Any, methodSymAny: Any)(using quotes: Quotes): Expr[Any] =
    import quotes.reflect.* // Import reflection types here
    val ownerSym = ownerSymAny.asInstanceOf[Symbol] // Cast Any to Symbol
    val methodSym = methodSymAny.asInstanceOf[Symbol] // Cast Any to Symbol

    val companionSym = ownerSym.companionModule
    val methodSymOpt = companionSym.declaredMethod(methodSym.name).headOption.getOrElse {
      report.errorAndAbort(
        s"Could not find method symbol for '${methodSym.name}' in ${companionSym.fullName}"
      )
    }
    Select(Ref(companionSym), methodSymOpt).etaExpand(Symbol.spliceOwner).asExprOf[Any]

  // Helper to parse @Tool annotation arguments
  def parseToolParams(termAny: Any)(using quotes: Quotes): (Option[String], Option[String], List[String]) =
    import quotes.reflect.* // Import reflection types here
    val term = termAny.asInstanceOf[Term] // Cast Any to Term

    var toolName: Option[String] = None
    var toolDesc: Option[String] = None
    var toolTags: List[String] = Nil

    def parseOptionString(argTerm: Term): Option[String] = argTerm match {
      case Apply(TypeApply(Select(Ident("Some"), "apply"), _), List(Literal(StringConstant(s)))) => Some(s)
      case Apply(Select(Ident("Some"), "apply"), List(Literal(StringConstant(s)))) => Some(s)
      case Select(Ident("None"), _) | Ident("None") => None
      case _ => None
    }

    def parseListString(argTerm: Term): List[String] = argTerm match {
      case Apply(TypeApply(Select(Ident("List"), "apply"), _), elems) =>
        elems.collect { case Literal(StringConstant(item)) => item }
      case Select(Ident("Nil"), _) | Apply(TypeApply(Select(Ident("List"), "apply"), _), Nil) => Nil
      case _ => Nil
    }

    term match {
      case Apply(Select(New(_), _), argTerms) =>
        argTerms.foreach {
          case NamedArg("name", valueTerm) => toolName = parseOptionString(valueTerm)
          case NamedArg("description", valueTerm) => toolDesc = parseOptionString(valueTerm)
          case NamedArg("tags", valueTerm) => toolTags = parseListString(valueTerm)
          case _ => ()
        }
      case _ => ()
    }
    (toolName, toolDesc, toolTags)

  // Helper to parse @Prompt annotation arguments
  def parsePromptParams(termAny: Any)(using quotes: Quotes): (Option[String], Option[String]) =
    import quotes.reflect.* // Import reflection types here
    val term = termAny.asInstanceOf[Term] // Cast Any to Term

    var promptName: Option[String] = None
    var promptDesc: Option[String] = None

    def parseOptionString(argTerm: Term): Option[String] = argTerm match {
      case Apply(TypeApply(Select(Ident("Some"), "apply"), _), List(Literal(StringConstant(s)))) => Some(s)
      case Apply(Select(Ident("Some"), "apply"), List(Literal(StringConstant(s)))) => Some(s)
      case Select(Ident("None"), _) | Ident("None") => None
      case _ => None
    }

    term match {
      case Apply(Select(New(_), _), argTerms) =>
        argTerms.foreach {
          case NamedArg("name", valueTerm) => promptName = parseOptionString(valueTerm)
          case NamedArg("description", valueTerm) => promptDesc = parseOptionString(valueTerm)
          case _ => ()
        }
      case _ => ()
    }
    (promptName, promptDesc)

  // Helper to parse @PromptParam annotation arguments
  def parsePromptParamArgs(paramAnnotOptAny: Option[Any])(using quotes: Quotes): (Option[String], Boolean) =
    import quotes.reflect.* // Import reflection types here
    val paramAnnotOpt = paramAnnotOptAny.map(_.asInstanceOf[Term]) // Cast Option[Any] to Option[Term]

    paramAnnotOpt match {
      case Some(annotTerm) =>
        var paramDesc: Option[String] = None
        var paramRequired: Boolean = true // Default required for @PromptParam
        annotTerm match {
          case Apply(_, args) => // args is defined here
            args.foreach {
              case Literal(StringConstant(s)) => paramDesc = Some(s)
              case NamedArg("description", Literal(StringConstant(s))) => paramDesc = Some(s)
              case NamedArg("required", Literal(BooleanConstant(b))) => paramRequired = b
              case Literal(BooleanConstant(b)) if args.size > 1 => paramRequired = b
              case _ => ()
            }
          case _ => ()
        }
        (paramDesc, paramRequired)
      case None => (None, true) // Default if no @PromptParam
    }

  // Helper to parse @Resource annotation arguments
  def parseResourceParams(termAny: Any)(using quotes: Quotes): (String, Option[String], Option[String], Option[String]) =
    import quotes.reflect.* // Import reflection types here
    val term = termAny.asInstanceOf[Term] // Cast Any to Term

    var uri: String = ""
    var resourceName: Option[String] = None
    var resourceDesc: Option[String] = None
    var mimeType: Option[String] = None

    def parseOptionString(argTerm: Term): Option[String] = argTerm match {
      case Apply(TypeApply(Select(Ident("Some"), "apply"), _), List(Literal(StringConstant(s)))) => Some(s)
      case Apply(Select(Ident("Some"), "apply"), List(Literal(StringConstant(s)))) => Some(s)
      case Select(Ident("None"), _) | Ident("None") => None
      case _ => None
    }

    term match {
      case Apply(Select(New(_), _), argTerms) =>
        argTerms.foreach {
          case Literal(StringConstant(s)) if uri.isEmpty => uri = s
          case NamedArg("uri", Literal(StringConstant(s))) => uri = s
          case NamedArg("name", valueTerm) => resourceName = parseOptionString(valueTerm)
          case NamedArg("description", valueTerm) => resourceDesc = parseOptionString(valueTerm)
          case NamedArg("mimeType", valueTerm) => mimeType = parseOptionString(valueTerm)
          case _ => ()
        }
      case _ => ()
    }
    if uri.isEmpty then report.errorAndAbort("@Resource annotation must have a 'uri' parameter.")
    (uri, resourceName, resourceDesc, mimeType)

  // Helper method to invoke a function
  def invokeFunctionWithArgs(function: Any, args: List[Any]): Any = {
    // We need to determine the actual types of the parameters
    // This is for catching string to enum conversion special case
    val fnMethod = try {
      function.getClass.getMethods.find(m => 
        (m.getName == "apply" && m.getParameterCount == args.length) ||
        (m.getParameterCount == args.length && m.getReturnType != Void.TYPE)
      )
    } catch { case _: Exception => None }
    
    // If we found a method, we can check its parameter types for enums
    val processedArgs = if (fnMethod.isDefined) {
      val paramTypes = fnMethod.get.getParameterTypes
      args.zip(paramTypes).map { case (arg, paramType) =>
        // Check if we need to convert string to enum 
        (arg, paramType) match {
          // String to Enum conversion
          case (s: String, cls) if cls.isEnum || (cls.getName.contains("$") && 
              (cls.getDeclaredFields.exists(_.getName == "$values") || 
              cls.getDeclaredMethods.exists(_.getName == "values"))) =>
            // Try to convert the string to the enum value
            try {
              System.err.println(s"[MacroUtils] Try to convert string '$s' to enum type ${cls.getName}")
              // Try to use our JacksonUtils helper method
              val enumValue = JacksonUtils.convertStringToEnum(s, cls)
              enumValue.getOrElse {
                System.err.println(s"[MacroUtils] Failed to convert '$s' to enum ${cls.getName}")
                arg // Keep original value if conversion failed
              }
            } catch {
              case e: Exception => 
                System.err.println(s"[MacroUtils] Error converting string to enum: ${e.getMessage}")
                arg // Return original on error
            }
          // Pass other values through
          case _ => arg
        }
      }
    } else {
      // If we couldn't determine parameter types, proceed with original args
      args
    }
    
    // Now continue with the normal function invocation logic
    val argCount = processedArgs.length
    function match {
      case f: Function0[?] if argCount == 0 => f()
      case f: Function1[?, ?] if argCount == 1 => f.asInstanceOf[Function1[Any, Any]](processedArgs(0))
      case f: Function2[?, ?, ?] if argCount == 2 => f.asInstanceOf[Function2[Any, Any, Any]](processedArgs(0), processedArgs(1))
      case f: Function3[?, ?, ?, ?] if argCount == 3 => f.asInstanceOf[Function3[Any, Any, Any, Any]](processedArgs(0), processedArgs(1), processedArgs(2))
      case f: Function4[?, ?, ?, ?, ?] if argCount == 4 => f.asInstanceOf[Function4[Any, Any, Any, Any, Any]](processedArgs(0), processedArgs(1), processedArgs(2), processedArgs(3))
      case f: Function5[?, ?, ?, ?, ?, ?] if argCount == 5 => f.asInstanceOf[Function5[Any, Any, Any, Any, Any, Any]](processedArgs(0), processedArgs(1), processedArgs(2), processedArgs(3), processedArgs(4))
      case f: Function6[?, ?, ?, ?, ?, ?, ?] if argCount == 6 => f.asInstanceOf[Function6[Any, Any, Any, Any, Any, Any, Any]](processedArgs(0), processedArgs(1), processedArgs(2), processedArgs(3), processedArgs(4), processedArgs(5))
      case f: Function7[?, ?, ?, ?, ?, ?, ?, ?] if argCount == 7 => f.asInstanceOf[Function7[Any, Any, Any, Any, Any, Any, Any, Any]](processedArgs(0), processedArgs(1), processedArgs(2), processedArgs(3), processedArgs(4), processedArgs(5), processedArgs(6))
      case f: Function8[?, ?, ?, ?, ?, ?, ?, ?, ?] if argCount == 8 => f.asInstanceOf[Function8[Any, Any, Any, Any, Any, Any, Any, Any, Any]](processedArgs(0), processedArgs(1), processedArgs(2), processedArgs(3), processedArgs(4), processedArgs(5), processedArgs(6), processedArgs(7))
      case f: Function9[?, ?, ?, ?, ?, ?, ?, ?, ?, ?] if argCount == 9 => f.asInstanceOf[Function9[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]](processedArgs(0), processedArgs(1), processedArgs(2), processedArgs(3), processedArgs(4), processedArgs(5), processedArgs(6), processedArgs(7), processedArgs(8))
      case f: Function10[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] if argCount == 10 => f.asInstanceOf[Function10[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]](processedArgs(0), processedArgs(1), processedArgs(2), processedArgs(3), processedArgs(4), processedArgs(5), processedArgs(6), processedArgs(7), processedArgs(8), processedArgs(9))
      case f: Function11[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] if argCount == 11 => f.asInstanceOf[Function11[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]](processedArgs(0), processedArgs(1), processedArgs(2), processedArgs(3), processedArgs(4), processedArgs(5), processedArgs(6), processedArgs(7), processedArgs(8), processedArgs(9), processedArgs(10))
      case f: Function12[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] if argCount == 12 => f.asInstanceOf[Function12[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]](processedArgs(0), processedArgs(1), processedArgs(2), processedArgs(3), processedArgs(4), processedArgs(5), processedArgs(6), processedArgs(7), processedArgs(8), processedArgs(9), processedArgs(10), processedArgs(11))
      case f: Function13[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] if argCount == 13 => f.asInstanceOf[Function13[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]](processedArgs(0), processedArgs(1), processedArgs(2), processedArgs(3), processedArgs(4), processedArgs(5), processedArgs(6), processedArgs(7), processedArgs(8), processedArgs(9), processedArgs(10), processedArgs(11), processedArgs(12))
      case f: Function14[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] if argCount == 14 => f.asInstanceOf[Function14[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]](processedArgs(0), processedArgs(1), processedArgs(2), processedArgs(3), processedArgs(4), processedArgs(5), processedArgs(6), processedArgs(7), processedArgs(8), processedArgs(9), processedArgs(10), processedArgs(11), processedArgs(12), processedArgs(13))
      case f: Function15[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] if argCount == 15 => f.asInstanceOf[Function15[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]](processedArgs(0), processedArgs(1), processedArgs(2), processedArgs(3), processedArgs(4), processedArgs(5), processedArgs(6), processedArgs(7), processedArgs(8), processedArgs(9), processedArgs(10), processedArgs(11), processedArgs(12), processedArgs(13), processedArgs(14))
      case f: Function16[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] if argCount == 16 => f.asInstanceOf[Function16[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]](processedArgs(0), processedArgs(1), processedArgs(2), processedArgs(3), processedArgs(4), processedArgs(5), processedArgs(6), processedArgs(7), processedArgs(8), processedArgs(9), processedArgs(10), processedArgs(11), processedArgs(12), processedArgs(13), processedArgs(14), processedArgs(15))
      case f: Function17[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] if argCount == 17 => f.asInstanceOf[Function17[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]](processedArgs(0), processedArgs(1), processedArgs(2), processedArgs(3), processedArgs(4), processedArgs(5), processedArgs(6), processedArgs(7), processedArgs(8), processedArgs(9), processedArgs(10), processedArgs(11), processedArgs(12), processedArgs(13), processedArgs(14), processedArgs(15), processedArgs(16))
      case f: Function18[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] if argCount == 18 => f.asInstanceOf[Function18[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]](processedArgs(0), processedArgs(1), processedArgs(2), processedArgs(3), processedArgs(4), processedArgs(5), processedArgs(6), processedArgs(7), processedArgs(8), processedArgs(9), processedArgs(10), processedArgs(11), processedArgs(12), processedArgs(13), processedArgs(14), processedArgs(15), processedArgs(16), processedArgs(17))
      case f: Function19[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] if argCount == 19 => f.asInstanceOf[Function19[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]](processedArgs(0), processedArgs(1), processedArgs(2), processedArgs(3), processedArgs(4), processedArgs(5), processedArgs(6), processedArgs(7), processedArgs(8), processedArgs(9), processedArgs(10), processedArgs(11), processedArgs(12), processedArgs(13), processedArgs(14), processedArgs(15), processedArgs(16), processedArgs(17), processedArgs(18))
      case f: Function20[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] if argCount == 20 => f.asInstanceOf[Function20[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]].apply(processedArgs(0), processedArgs(1), processedArgs(2), processedArgs(3), processedArgs(4), processedArgs(5), processedArgs(6), processedArgs(7), processedArgs(8), processedArgs(9), processedArgs(10), processedArgs(11), processedArgs(12), processedArgs(13), processedArgs(14), processedArgs(15), processedArgs(16), processedArgs(17), processedArgs(18), processedArgs(19))
      case f: Function21[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] if argCount == 21 => f.asInstanceOf[Function21[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]].apply(processedArgs(0), processedArgs(1), processedArgs(2), processedArgs(3), processedArgs(4), processedArgs(5), processedArgs(6), processedArgs(7), processedArgs(8), processedArgs(9), processedArgs(10), processedArgs(11), processedArgs(12), processedArgs(13), processedArgs(14), processedArgs(15), processedArgs(16), processedArgs(17), processedArgs(18), processedArgs(19), processedArgs(20))
      case f: Function22[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] if argCount == 22 => f.asInstanceOf[Function22[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]].apply(processedArgs(0), processedArgs(1), processedArgs(2), processedArgs(3), processedArgs(4), processedArgs(5), processedArgs(6), processedArgs(7), processedArgs(8), processedArgs(9), processedArgs(10), processedArgs(11), processedArgs(12), processedArgs(13), processedArgs(14), processedArgs(15), processedArgs(16), processedArgs(17), processedArgs(18), processedArgs(19), processedArgs(20), processedArgs(21))
      // Reflection fallback
      case _ =>
        try {
          val methodToInvoke = function.getClass.getMethods.find { m =>
            (m.getName == "apply" && m.getParameterCount == argCount) ||
              (m.getParameterCount == argCount && m.getReturnType != Void.TYPE)
          }.getOrElse {
            throw new RuntimeException(s"Could not find suitable method with $argCount parameters on ${function.getClass.getName}")
          }
          val invokeArgs = processedArgs.map(_.asInstanceOf[Object]).toArray
          methodToInvoke.invoke(function, invokeArgs*)
        } catch {
          case e: Exception =>
            System.err.println(s"Reflection invocation failed for function ${function.getClass.getName} with args $processedArgs: ${e.getMessage}")
            throw new RuntimeException("Failed to invoke function via reflection: " + e.getMessage, e)
        }
    }
  }
end MacroUtils