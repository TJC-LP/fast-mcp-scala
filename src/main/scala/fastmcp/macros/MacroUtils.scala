package fastmcp.macros

import fastmcp.core.*
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
end MacroUtils