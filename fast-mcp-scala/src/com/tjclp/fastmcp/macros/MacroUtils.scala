package com.tjclp.fastmcp
package macros

import scala.quoted.*

import io.circe.Json
import io.circe.JsonObject

import com.tjclp.fastmcp.runtime.RefResolver

/** Metadata extracted from @Param/@ToolParam annotations */
case class ParamMetadata(
    description: Option[String] = None,
    examples: List[String] = Nil,
    required: Boolean = true,
    schema: Option[String] = None
)

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

  private def stripTerm(using quotes: Quotes)(term: quotes.reflect.Term): quotes.reflect.Term =
    import quotes.reflect.*
    term match {
      case Inlined(_, _, inner) => stripTerm(inner)
      case Typed(inner, _) => stripTerm(inner)
      case Block(_, inner) => stripTerm(inner)
      case _ => term
    }

  /** Parses a List[String] from an annotation argument term.
    *
    * Handles list literals, `::` chains, SeqLiteral, and Nil.
    */
  private def parseListString(using quotes: Quotes)(argTerm: quotes.reflect.Term): List[String] =
    import quotes.reflect.*

    def loop(term: Term): List[String] = term match {
      case Inlined(_, _, inner) => loop(inner)
      case Typed(inner, _) => loop(inner)
      case Repeated(elems, _) =>
        elems.flatMap(loop)
      case Block(stats, expr) =>
        val statStrings = stats.collect { case ValDef(_, _, Some(rhs)) => loop(rhs) }.flatten
        statStrings ++ loop(expr)
      case Apply(_, elems) =>
        elems.flatMap(loop)
      case Literal(StringConstant(item)) =>
        List(item)
      case Select(_, "Nil") | Ident("Nil") =>
        Nil
      case _ => Nil
    }

    loop(argTerm)

  // Helper to parse @Tool annotation arguments
  def parseToolParams(using quotes: Quotes)(
      term: quotes.reflect.Term
  ): (Option[String], Option[String], List[String]) =
    import quotes.reflect.*

    var toolName: Option[String] = None
    var toolDesc: Option[String] = None
    var toolTags: List[String] = Nil

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
              // Named description
              case NamedArg("description", valueTerm) =>
                stripTerm(valueTerm) match {
                  case Literal(StringConstant(s)) => paramDesc = Some(s)
                  case _ => ()
                }
              // Named required
              case NamedArg("required", valueTerm) =>
                stripTerm(valueTerm) match {
                  case Literal(BooleanConstant(b)) =>
                    paramRequired = b
                    requiredSetByName = true
                  case _ => ()
                }
              // Positional description/required handling
              case term =>
                stripTerm(term) match {
                  case Literal(StringConstant(s)) if paramDesc.isEmpty =>
                    paramDesc = Some(s)
                    descriptionSetPositionally = true
                  case Literal(BooleanConstant(b))
                      if descriptionSetPositionally && !requiredSetByName =>
                    paramRequired = b
                  case _ => ()
                }
            }
          case _ => () // Ignore if annotation term is not an Apply
        }
        (paramDesc, paramRequired)
      case None => (None, true) // Defaults if no @PromptParam
    }

  // Generic helper to extract parameter annotations (Param or legacy specific ones)
  // First checks for new @Param, then falls back to context-specific annotation
  def extractParamAnnotation(using quotes: Quotes)(
      sym: quotes.reflect.Symbol,
      fallbackAnnotationType: Option[String] = None
  ): Option[quotes.reflect.Term] =

    // First check for the new unified @Param annotation
    val paramAnnot = extractAnnotation[com.tjclp.fastmcp.core.Param](sym)
    if (paramAnnot.isDefined) return paramAnnot

    // Fall back to specific annotations based on context
    fallbackAnnotationType match {
      case Some("Tool") => extractAnnotation[com.tjclp.fastmcp.core.ToolParam](sym)
      case Some("Resource") => extractAnnotation[com.tjclp.fastmcp.core.ResourceParam](sym)
      case Some("Prompt") => extractAnnotation[com.tjclp.fastmcp.core.PromptParam](sym)
      case _ => None
    }

  // Helper to parse @Param annotation arguments for @Tool methods
  // Returns: (description: Option[String], examples: List[String], required: Boolean, schema: Option[String])
  def parseToolParam(using quotes: Quotes)(
      paramAnnotOpt: Option[quotes.reflect.Term]
  ): (Option[String], List[String], Boolean, Option[String]) =
    import quotes.reflect.*

    paramAnnotOpt match {
      case Some(annotTerm) =>
        var paramDesc: Option[String] = None
        var paramExamples: List[String] = Nil
        var paramRequired: Boolean = true // Default required for @Param
        var paramSchema: Option[String] = None
        var examplesSetByName = false
        var requiredSetByName = false
        var schemaSetByName = false

        annotTerm match {
          case Apply(_, args) =>
            var positionalIndex = 0
            args.foreach {
              // Named description
              case NamedArg("description", valueTerm) =>
                stripTerm(valueTerm) match {
                  case Literal(StringConstant(s)) => paramDesc = Some(s)
                  case _ => ()
                }
              // Named examples (List[String])
              case NamedArg("examples", valueTerm) =>
                paramExamples = parseListString(valueTerm)
                examplesSetByName = true
              // Named required
              case NamedArg("required", valueTerm) =>
                stripTerm(valueTerm) match {
                  case Literal(BooleanConstant(b)) =>
                    paramRequired = b
                    requiredSetByName = true
                  case _ => ()
                }
              // Named schema
              case NamedArg("schema", valueTerm) =>
                paramSchema = parseOptionStringLiteral(valueTerm)
                schemaSetByName = true
              // Positional args: description, examples, required, schema
              case term =>
                positionalIndex match {
                  case 0 =>
                    stripTerm(term) match {
                      case Literal(StringConstant(s)) if paramDesc.isEmpty =>
                        paramDesc = Some(s)
                      case _ => ()
                    }
                  case 1 if !examplesSetByName =>
                    val parsed = parseListString(term)
                    if parsed.nonEmpty then paramExamples = parsed
                  case 2 if !requiredSetByName =>
                    stripTerm(term) match {
                      case Literal(BooleanConstant(b)) => paramRequired = b
                      case _ => ()
                    }
                  case 3 if !schemaSetByName =>
                    paramSchema = parseOptionStringLiteral(term)
                  case _ => ()
                }
                positionalIndex += 1
            }
          case _ => () // Ignore if annotation term is not an Apply
        }
        (paramDesc, paramExamples, paramRequired, paramSchema)
      case None => (None, Nil, true, None) // Defaults if no @Param
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

  // Helper to parse Option[String] literals from annotation arguments
  private def parseOptionStringLiteral(using quotes: Quotes)(
      argTerm: quotes.reflect.Term
  ): Option[String] =
    import quotes.reflect.*

    def parseLiteral(term: Term): Option[String] = stripTerm(term) match {
      case Literal(StringConstant(s)) => Some(s)
      case _ => None
    }

    stripTerm(argTerm) match {
      // Matches Some("literal") created via Some.apply[String]("literal")
      case Apply(TypeApply(Select(Ident("Some"), "apply"), _), List(arg)) =>
        parseLiteral(arg)
      // Matches Some("literal") created via Some("literal")
      case Apply(Select(Ident("Some"), "apply"), List(arg)) =>
        parseLiteral(arg)
      // Matches None
      case Select(Ident("None"), _) | Ident("None") => None
      case _ =>
        // report.warning(s"Could not parse Option[String] from term: ${argTerm.show}") // Optional warning
        None
    }

  // Helper method to invoke a function (runtime)
  // Delegates to the RefResolver implementation which uses MethodHandles
  def invokeFunctionWithArgs(function: Any, args: List[Any]): Any =
    RefResolver.invokeFunctionWithArgs(function, args)

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

  /** Injects all @Param metadata into JSON schema properties.
    *   - description: Added to each property object
    *   - examples: Added as array to each property object (JSON Schema format)
    *   - required: Updates top-level "required" array
    *   - schema: Replaces entire property definition with custom schema
    */
  def injectParamMetadata(schemaJson: Json, metadataMap: Map[String, ParamMetadata]): Json = {
    import io.circe.parser.parse

    if (metadataMap.isEmpty) return schemaJson

    val cursor = schemaJson.hcursor

    // 1. Get current required array (or empty)
    val currentRequired = cursor.downField("required").as[List[String]].getOrElse(Nil).toSet

    // 2. Compute new required array based on metadata
    // Only modify required status for params that have metadata
    val newRequired = metadataMap.foldLeft(currentRequired) { case (acc, (name, meta)) =>
      if (meta.required) acc + name else acc - name
    }

    // 3. Update properties with description, examples, or custom schema
    val maybeProps = cursor.downField("properties").focus
    val newPropsJson = maybeProps.flatMap(_.asObject).map { propsObj =>
      val updatedFields = propsObj.toMap.map { case (fieldName, fieldJson) =>
        metadataMap.get(fieldName) match {
          case Some(meta) =>
            meta.schema match {
              case Some(customSchema) =>
                // Replace entire property with parsed custom schema
                fieldName -> parse(customSchema).getOrElse(fieldJson)
              case None =>
                // Add description and examples to existing property
                val baseObj = fieldJson.asObject.getOrElse(JsonObject.empty)
                val withDesc = meta.description.fold(baseObj)(d =>
                  baseObj.add("description", Json.fromString(d))
                )
                val withExamples =
                  if (meta.examples.nonEmpty) {
                    val examplesJson = Json.fromValues(meta.examples.map(Json.fromString))
                    withDesc.add("examples", examplesJson)
                  } else withDesc
                fieldName -> Json.fromJsonObject(withExamples)
            }
          case None => fieldName -> fieldJson
        }
      }
      JsonObject.fromMap(updatedFields)
    }

    // 4. Rebuild schema with updated properties and required
    schemaJson.mapObject { obj =>
      val withProps = newPropsJson.fold(obj)(p => obj.add("properties", Json.fromJsonObject(p)))
      if (newRequired.nonEmpty)
        withProps.add("required", Json.fromValues(newRequired.toSeq.sorted.map(Json.fromString)))
      else
        withProps.remove("required")
    }
  }
end MacroUtils
