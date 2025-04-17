package fastmcp.macros

import scala.quoted.*
import scala.util.Try
import io.circe.{Json, JsonObject}

/** Utility methods shared between the processor objects (Compressed)
  */
private[macros] object MacroUtils:

  /** Generic utility to extract an annotation of type `A` from a symbol. Returns the annotation
    * term if present, otherwise None.
    */
  def extractAnnotation[A: Type](using quotes: Quotes)(
      sym: quotes.reflect.Symbol
  ): Option[quotes.reflect.Term] =
    import quotes.reflect.*
    val annotTpe = TypeRepr.of[A]
    sym.annotations.find(_.tpe <:< annotTpe)

  /** Generic utility to extract all annotations of type `A` from a symbol. Returns a list of
    * annotation terms.
    */
  def extractAnnotations[A: Type](using quotes: Quotes)(
      sym: quotes.reflect.Symbol
  ): List[quotes.reflect.Term] =
    import quotes.reflect.*
    val annotTpe = TypeRepr.of[A]
    sym.annotations.filter(_.tpe <:< annotTpe)

  // Helper to parse Option[String] literals from annotation arguments
  private def parseOptionStringLiteral(using quotes: Quotes)(
      argTerm: quotes.reflect.Term
  ): Option[String] =
    import quotes.reflect.*
    argTerm match {
      // Matches Some("literal") created via Some.apply[String]("literal")
      case Apply(TypeApply(Select(Ident("Some"), "apply"), _), List(Literal(StringConstant(s)))) =>
        Some(s)
      // Matches Some("literal") created via Some("literal")
      case Apply(Select(Ident("Some"), "apply"), List(Literal(StringConstant(s)))) => Some(s)
      // Matches None
      case Select(Ident("None"), _) | Ident("None") => None
      case _ =>
        // report.warning(s"Could not parse Option[String] from term: ${argTerm.show}") // Optional warning
        None
    }

  // Gets a reference to the method within its owner object
  def getMethodRefExpr(using
      quotes: Quotes
  )(ownerSym: quotes.reflect.Symbol, methodSym: quotes.reflect.Symbol): Expr[Any] =
    import quotes.reflect.*
    val companionSym = ownerSym.companionModule
    val methodSymOpt = companionSym.declaredMethod(methodSym.name).headOption.getOrElse {
      report.errorAndAbort(
        s"Could not find method symbol for '${methodSym.name}' in ${companionSym.fullName}"
      )
    }
    Select(Ref(companionSym), methodSymOpt).etaExpand(Symbol.spliceOwner).asExprOf[Any]

  // Helper to parse @Tool annotation arguments
  def parseToolParams(using quotes: Quotes)(
      term: quotes.reflect.Term
  ): (Option[String], Option[String], List[String]) =
    import quotes.reflect.*

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
  def parsePromptParams(using quotes: Quotes)(
      term: quotes.reflect.Term
  ): (Option[String], Option[String]) =
    import quotes.reflect.*

    var promptName: Option[String] = None
    var promptDesc: Option[String] = None

    term match {
      case Apply(Select(New(_), _), argTerms) =>
        argTerms.foreach {
          case NamedArg("name", valueTerm) => promptName = parseOptionStringLiteral(valueTerm)
          case NamedArg("description", valueTerm) =>
            promptDesc = parseOptionStringLiteral(valueTerm)
          case _ => ()
        }
      case _ => ()
    }
    (promptName, promptDesc)

  // Helper to parse @PromptParam annotation arguments
  def parsePromptParamArgs(using quotes: Quotes)(
      paramAnnotOpt: Option[quotes.reflect.Term]
  ): (Option[String], Boolean) =
    import quotes.reflect.*

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
              case Literal(BooleanConstant(b))
                  if descriptionSetPositionally && !requiredSetByName =>
                paramRequired = b
              // Ignore other argument types or structures
              case _ => ()
            }
          case _ => () // Ignore if annotation term is not an Apply
        }
        (paramDesc, paramRequired)
      case None => (None, true) // Defaults if no @PromptParam
    }

  // Helper to parse @Param annotation arguments for @Tool methods
  // Returns: (description: Option[String], example: Option[String], required: Boolean, schema: Option[String])
  def parseToolParam(using quotes: Quotes)(
      paramAnnotOpt: Option[quotes.reflect.Term]
  ): (Option[String], Option[String], Boolean, Option[String]) =
    import quotes.reflect.*

    paramAnnotOpt match {
      case Some(annotTerm) =>
        var paramDesc: Option[String] = None
        var paramExample: Option[String] = None
        var paramRequired: Boolean = true // Default required for @Param
        var paramSchema: Option[String] = None
        var descriptionSetPositionally = false
        var exampleSetByName = false
        var requiredSetByName = false
        var schemaSetByName = false

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
              // Named example
              case NamedArg("example", valueTerm) =>
                valueTerm match {
                  case Apply(
                        TypeApply(Select(Ident("Some"), "apply"), _),
                        List(Literal(StringConstant(ex)))
                      ) =>
                    paramExample = Some(ex)
                    exampleSetByName = true
                  case Select(Ident("None"), _) | Ident("None") =>
                    paramExample = None
                  case _ => ()
                }
              // Named required
              case NamedArg("required", Literal(BooleanConstant(b))) =>
                paramRequired = b
                requiredSetByName = true
              // Named schema
              case NamedArg("schema", valueTerm) =>
                valueTerm match {
                  case Apply(
                        TypeApply(Select(Ident("Some"), "apply"), _),
                        List(Literal(StringConstant(sch)))
                      ) =>
                    paramSchema = Some(sch)
                    schemaSetByName = true
                  case Select(Ident("None"), _) | Ident("None") =>
                    paramSchema = None
                  case _ => ()
                }
              // Positional boolean: Only if description was set positionally and required wasn't set by name.
              case Literal(BooleanConstant(b))
                  if descriptionSetPositionally && !requiredSetByName =>
                paramRequired = b
              // Ignore other argument types or structures
              case _ => ()
            }
          case _ => () // Ignore if annotation term is not an Apply
        }
        (paramDesc, paramExample, paramRequired, paramSchema)
      case None => (None, None, true, None) // Defaults if no @Param
    }

  // Helper to parse @Resource annotation arguments
  def parseResourceParams(using quotes: Quotes)(
      term: quotes.reflect.Term
  ): (String, Option[String], Option[String], Option[String]) =
    import quotes.reflect.*

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
          case NamedArg("description", valueTerm) =>
            resourceDesc = parseOptionStringLiteral(valueTerm)
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
      case f: Function2[?, ?, ?] if argCount == 2 =>
        f.asInstanceOf[Function2[Any, Any, Any]](args(0), args(1))
      case f: Function3[?, ?, ?, ?] if argCount == 3 =>
        f.asInstanceOf[Function3[Any, Any, Any, Any]](args(0), args(1), args(2))
      case f: Function4[?, ?, ?, ?, ?] if argCount == 4 =>
        f.asInstanceOf[Function4[Any, Any, Any, Any, Any]](args(0), args(1), args(2), args(3))
      case f: Function5[?, ?, ?, ?, ?, ?] if argCount == 5 =>
        f.asInstanceOf[Function5[Any, Any, Any, Any, Any, Any]](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4)
        )
      case f: Function6[?, ?, ?, ?, ?, ?, ?] if argCount == 6 =>
        f.asInstanceOf[Function6[Any, Any, Any, Any, Any, Any, Any]](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5)
        )
      case f: Function7[?, ?, ?, ?, ?, ?, ?, ?] if argCount == 7 =>
        f.asInstanceOf[Function7[Any, Any, Any, Any, Any, Any, Any, Any]](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6)
        )
      case f: Function8[?, ?, ?, ?, ?, ?, ?, ?, ?] if argCount == 8 =>
        f.asInstanceOf[Function8[Any, Any, Any, Any, Any, Any, Any, Any, Any]](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7)
        )
      case f: Function9[?, ?, ?, ?, ?, ?, ?, ?, ?, ?] if argCount == 9 =>
        f.asInstanceOf[Function9[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7),
          args(8)
        )
      case f: Function10[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] if argCount == 10 =>
        f.asInstanceOf[Function10[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7),
          args(8),
          args(9)
        )
      case f: Function11[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] if argCount == 11 =>
        f.asInstanceOf[Function11[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7),
          args(8),
          args(9),
          args(10)
        )
      case f: Function12[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] if argCount == 12 =>
        f.asInstanceOf[Function12[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7),
          args(8),
          args(9),
          args(10),
          args(11)
        )
      case f: Function13[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] if argCount == 13 =>
        f.asInstanceOf[
          Function13[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]
        ](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7),
          args(8),
          args(9),
          args(10),
          args(11),
          args(12)
        )
      case f: Function14[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] if argCount == 14 =>
        f.asInstanceOf[
          Function14[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]
        ](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7),
          args(8),
          args(9),
          args(10),
          args(11),
          args(12),
          args(13)
        )
      case f: Function15[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] if argCount == 15 =>
        f.asInstanceOf[
          Function15[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]
        ](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7),
          args(8),
          args(9),
          args(10),
          args(11),
          args(12),
          args(13),
          args(14)
        )
      case f: Function16[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] if argCount == 16 =>
        f.asInstanceOf[Function16[
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any
        ]](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7),
          args(8),
          args(9),
          args(10),
          args(11),
          args(12),
          args(13),
          args(14),
          args(15)
        )
      case f: Function17[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] if argCount == 17 =>
        f.asInstanceOf[Function17[
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any
        ]](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7),
          args(8),
          args(9),
          args(10),
          args(11),
          args(12),
          args(13),
          args(14),
          args(15),
          args(16)
        )
      case f: Function18[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]
          if argCount == 18 =>
        f.asInstanceOf[Function18[
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any
        ]](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7),
          args(8),
          args(9),
          args(10),
          args(11),
          args(12),
          args(13),
          args(14),
          args(15),
          args(16),
          args(17)
        )
      case f: Function19[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]
          if argCount == 19 =>
        f.asInstanceOf[Function19[
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any
        ]](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7),
          args(8),
          args(9),
          args(10),
          args(11),
          args(12),
          args(13),
          args(14),
          args(15),
          args(16),
          args(17),
          args(18)
        )
      case f: Function20[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]
          if argCount == 20 =>
        f.asInstanceOf[Function20[
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any
        ]].apply(
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7),
          args(8),
          args(9),
          args(10),
          args(11),
          args(12),
          args(13),
          args(14),
          args(15),
          args(16),
          args(17),
          args(18),
          args(19)
        )
      case f: Function21[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]
          if argCount == 21 =>
        f.asInstanceOf[Function21[
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any
        ]].apply(
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7),
          args(8),
          args(9),
          args(10),
          args(11),
          args(12),
          args(13),
          args(14),
          args(15),
          args(16),
          args(17),
          args(18),
          args(19),
          args(20)
        )
      case f: Function22[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]
          if argCount == 22 =>
        f.asInstanceOf[Function22[
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any
        ]].apply(
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7),
          args(8),
          args(9),
          args(10),
          args(11),
          args(12),
          args(13),
          args(14),
          args(15),
          args(16),
          args(17),
          args(18),
          args(19),
          args(20),
          args(21)
        )
      // Reflection fallback for other cases or non-Function types with matching arity 'apply'
      case _ =>
        Try {
          // Find a method named "apply" or any method with the correct parameter count.
          // This is a basic fallback and might not find the intended method if overloaded.
          val methodToInvoke = function.getClass.getMethods
            .find { m =>
              m.getParameterCount == argCount && (m.getName == "apply" || m.getReturnType != Void.TYPE)
            }
            .getOrElse {
              throw new NoSuchMethodException(
                s"Suitable method with $argCount parameters not found on ${function.getClass.getName}"
              )
            }
          // Prepare arguments for reflection call
          val invokeArgs = args.map(_.asInstanceOf[Object]).toArray
          methodToInvoke.invoke(function, invokeArgs*)
        }.recover { case e: Exception =>
          System.err.println(
            s"Reflection invocation failed for ${function.getClass.getName} with $argCount args: ${e.getMessage}"
          )
          throw new RuntimeException(
            s"Failed to invoke function via reflection: ${e.getMessage}",
            e
          )
        }.get // Re-throw exception if recovery failed
    }
  }

  /** Takes a JSON schema potentially containing `$defs` and `$ref` and returns a new JSON schema
    * where all references are resolved and inlined.
    */
  def resolveJsonRefs(inputJson: Json): Json = {
    val cursor = inputJson.hcursor
    val definitions: Map[String, Json] = cursor
      .downField("$defs")
      .as[Map[String, Json]]
      .getOrElse(Map.empty)

    def resolve(currentJson: Json, defs: Map[String, Json]): Json = {
      currentJson.fold(
        jsonNull = Json.Null,
        jsonBoolean = Json.fromBoolean,
        jsonNumber = Json.fromJsonNumber,
        jsonString = Json.fromString,
        jsonArray = arr => Json.fromValues(arr.map(elem => resolve(elem, defs))),
        jsonObject = obj => {
          obj("$ref") match {
            case Some(refJson) if refJson.isString =>
              val refPath = refJson.asString.get
              // Assuming format like "#/$defs/DefinitionName"
              val defName = refPath.split('/').last
              defs.get(defName) match {
                case Some(definition) =>
                  // Recursively resolve within the definition itself
                  resolve(definition, defs)
                case None =>
                  // Reference not found, return the original ref object
                  currentJson
              }
            case _ =>
              // Not a $ref object or $ref is not a string,
              // resolve recursively within values.
              // Filter out the $defs key if encountered nested (shouldn't happen with root removal).
              Json.fromJsonObject(
                JsonObject.fromIterable(
                  obj.toIterable.filter(_._1 != "$defs").map { case (k, v) =>
                    (k, resolve(v, defs))
                  }
                )
              )
          }
        }
      )
    }

    // Remove top-level $defs before starting resolution
    val rootJsonWithoutDefs = inputJson.mapObject(_.remove("$defs"))
    resolve(rootJsonWithoutDefs, definitions)
  }

  /** Injects param descriptions (from @Param annotations) into the top-level "properties" fields of
    * the JSON schema. Returns a new Json with descriptions added.
    */
  def injectParamDescriptions(schemaJson: Json, descriptionMap: Map[String, String]): Json = {
    val cursor = schemaJson.hcursor

    // Navigate to properties object
    val maybeProps = cursor.downField("properties").focus

    maybeProps match {
      case Some(propsJson) if propsJson.isObject =>
        // Build new properties object with description injected
        val newPropsObj = propsJson.asObject.map { propsObj =>
          val updatedFields = propsObj.toMap.map { case (fieldName, fieldJson) =>
            descriptionMap.get(fieldName) match {
              case Some(desc) =>
                // Inject or replace description
                val newFieldObj = fieldJson.asObject match {
                  case Some(obj) =>
                    Json.fromJsonObject(obj.add("description", Json.fromString(desc)))
                  case None => fieldJson
                }
                fieldName -> newFieldObj
              case None =>
                fieldName -> fieldJson
            }
          }
          JsonObject.fromMap(updatedFields)
        }

        // Replace properties with updated object
        newPropsObj match {
          case Some(obj) =>
            cursor
              .withFocus(_.mapObject(_.add("properties", Json.fromJsonObject(obj))))
              .top
              .getOrElse(schemaJson)
          case None =>
            schemaJson
        }

      case _ =>
        // No properties object to inject into
        schemaJson
    }
  }
end MacroUtils
