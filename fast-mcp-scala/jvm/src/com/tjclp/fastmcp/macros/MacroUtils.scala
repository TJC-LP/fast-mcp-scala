package com.tjclp.fastmcp
package macros

import scala.quoted.*

import io.circe.Json
import io.circe.JsonObject

import com.tjclp.fastmcp.runtime.RefResolver

/** Metadata extracted from @Param annotations */
case class ParamMetadata(
    description: Option[String] = None,
    examples: List[String] = Nil,
    required: Boolean = true,
    schema: Option[String] = None
)

/** Recursive metadata tree used to inject `@Param` metadata into typed request schemas. */
case class SchemaMetadataNode(
    metadata: Option[ParamMetadata] = None,
    properties: Map[String, SchemaMetadataNode] = Map.empty,
    items: Option[SchemaMetadataNode] = None
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

  // Helper to parse @Param annotation arguments for prompts
  def parsePromptParamArgs(using quotes: Quotes)(
      paramAnnotOpt: Option[quotes.reflect.Term]
  ): (Option[String], Boolean) =
    import quotes.reflect.*

    paramAnnotOpt match {
      case Some(annotTerm) =>
        var paramDesc: Option[String] = None
        var paramRequired: Boolean = true // Default required for @Param
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
      case None => (None, true) // Defaults if no @Param
    }

  /** Extract the `@Param` annotation from a method parameter symbol, if present. */
  def extractParamAnnotation(using quotes: Quotes)(
      sym: quotes.reflect.Symbol
  ): Option[quotes.reflect.Term] =
    extractAnnotation[com.tjclp.fastmcp.core.Param](sym)

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

  def schemaMetadataForType[T: Type](using Quotes): Expr[SchemaMetadataNode] =
    import quotes.reflect.*
    schemaMetadataForTypeRepr(TypeRepr.of[T]).getOrElse('{ SchemaMetadataNode() })

  private def schemaMetadataForTypeRepr(using Quotes)(
      rawTpe: quotes.reflect.TypeRepr
  ): Option[Expr[SchemaMetadataNode]] =
    import quotes.reflect.*

    val tpe = rawTpe.dealias.simplified

    def hasDefaultValue(owner: Symbol, fieldIndex: Int): Boolean =
      if owner == Symbol.noSymbol then false
      else
        val candidateNames = List(
          s"$$lessinit$$greater$$default$$${fieldIndex + 1}",
          s"apply$$default$$${fieldIndex + 1}"
        )
        candidateNames.exists(name => owner.methodMember(name).nonEmpty)

    def fieldAnnotation(fieldSym: Symbol, ctorParams: List[Symbol]): Option[Term] =
      extractAnnotation[com.tjclp.fastmcp.core.Param](fieldSym).orElse {
        ctorParams
          .find(_.name == fieldSym.name)
          .flatMap(param => extractAnnotation[com.tjclp.fastmcp.core.Param](param))
      }

    tpe.asType match
      case '[Option[a]] =>
        schemaMetadataForTypeRepr(TypeRepr.of[a])
      case '[List[a]] =>
        schemaMetadataForTypeRepr(TypeRepr.of[a]).map { item =>
          '{ SchemaMetadataNode(items = Some($item)) }
        }
      case '[Seq[a]] =>
        schemaMetadataForTypeRepr(TypeRepr.of[a]).map { item =>
          '{ SchemaMetadataNode(items = Some($item)) }
        }
      case '[Array[a]] =>
        schemaMetadataForTypeRepr(TypeRepr.of[a]).map { item =>
          '{ SchemaMetadataNode(items = Some($item)) }
        }
      case _ =>
        val tpeSym = tpe.typeSymbol
        val isProduct = tpeSym.isClassDef && tpeSym.caseFields.nonEmpty

        if !isProduct then None
        else
          val ctorParams = tpeSym.primaryConstructor.paramSymss.flatten
          val companion = tpeSym.companionModule

          val entries = tpeSym.caseFields.zipWithIndex.flatMap { case (fieldSym, idx) =>
            val fieldTpe = tpe.memberType(fieldSym)
            val nested = schemaMetadataForTypeRepr(fieldTpe)
            val parsedMeta = fieldAnnotation(fieldSym, ctorParams).map { annot =>
              val (desc, examples, required, schema) = parseToolParam(Some(annot))
              if !required then
                val isOption = fieldTpe <:< TypeRepr.of[Option[?]]
                val hasDefault = hasDefaultValue(companion, idx)
                if !isOption && !hasDefault then
                  report.errorAndAbort(
                    s"Field '${fieldSym.name}' in typed request ${tpeSym.name} is marked as required=false " +
                      s"but is not an Option type and has no default value."
                  )
              ParamMetadata(desc, examples, required, schema)
            }

            if parsedMeta.isDefined || nested.isDefined then
              val fieldNameExpr = Expr(fieldSym.name)
              val isCollectionType = fieldTpe.asType match
                case '[List[?]] | '[Seq[?]] | '[Array[?]] => true
                case _ => false
              val metaExpr = parsedMeta match
                case Some(meta) =>
                  '{
                    Some(
                      ParamMetadata(
                        description = ${ Expr(meta.description) },
                        examples = ${ Expr(meta.examples) },
                        required = ${ Expr(meta.required) },
                        schema = ${ Expr(meta.schema) }
                      )
                    )
                  }
                case None => '{ None }

              val itemsExpr = nested match
                case Some(node) if isCollectionType =>
                  '{ Some($node) }
                case _ => '{ None }

              val propertiesExpr =
                if isCollectionType then '{ Map.empty[String, SchemaMetadataNode] }
                else
                  nested match
                    case Some(node) => '{ $node.properties }
                    case None => '{ Map.empty[String, SchemaMetadataNode] }

              Some(
                '{
                  $fieldNameExpr -> SchemaMetadataNode(
                    metadata = $metaExpr,
                    properties = $propertiesExpr,
                    items = $itemsExpr
                  )
                }
              )
            else None
          }

          if entries.nonEmpty then
            Some('{ SchemaMetadataNode(properties = Map(${ Varargs(entries) }*)) })
          else None

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

  // Helper to parse Option[Boolean] literals from annotation arguments
  private def parseOptionBooleanLiteral(using quotes: Quotes)(
      argTerm: quotes.reflect.Term
  ): Option[Boolean] =
    import quotes.reflect.*

    def parseLiteral(term: Term): Option[Boolean] = stripTerm(term) match {
      case Literal(BooleanConstant(b)) => Some(b)
      case _ => None
    }

    stripTerm(argTerm) match {
      case Apply(TypeApply(Select(Ident("Some"), "apply"), _), List(arg)) =>
        parseLiteral(arg)
      case Apply(Select(Ident("Some"), "apply"), List(arg)) =>
        parseLiteral(arg)
      case Select(Ident("None"), _) | Ident("None") => None
      case _ => None
    }

  /** Extract MCP ToolAnnotation hints from a @Tool annotation term.
    */
  def parseToolAnnotationHints(using quotes: Quotes)(
      term: quotes.reflect.Term
  ): (
      Option[String],
      Option[Boolean],
      Option[Boolean],
      Option[Boolean],
      Option[Boolean],
      Option[Boolean]
  ) =
    import quotes.reflect.*

    var title: Option[String] = None
    var readOnlyHint: Option[Boolean] = None
    var destructiveHint: Option[Boolean] = None
    var idempotentHint: Option[Boolean] = None
    var openWorldHint: Option[Boolean] = None
    var returnDirect: Option[Boolean] = None

    term match {
      case Apply(Select(New(_), _), argTerms) =>
        argTerms.foreach {
          case NamedArg("title", valueTerm) =>
            title = parseOptionStringLiteral(valueTerm)
          case NamedArg("readOnlyHint", valueTerm) =>
            readOnlyHint = parseOptionBooleanLiteral(valueTerm)
          case NamedArg("destructiveHint", valueTerm) =>
            destructiveHint = parseOptionBooleanLiteral(valueTerm)
          case NamedArg("idempotentHint", valueTerm) =>
            idempotentHint = parseOptionBooleanLiteral(valueTerm)
          case NamedArg("openWorldHint", valueTerm) =>
            openWorldHint = parseOptionBooleanLiteral(valueTerm)
          case NamedArg("returnDirect", valueTerm) =>
            returnDirect = parseOptionBooleanLiteral(valueTerm)
          case _ => ()
        }
      case _ => ()
    }
    (title, readOnlyHint, destructiveHint, idempotentHint, openWorldHint, returnDirect)

  // Helper method to invoke a function (runtime)
  // Delegates to the RefResolver implementation which uses MethodHandles
  def invokeFunctionWithArgs(function: Any, args: List[Any]): Any =
    RefResolver.invokeFunctionWithArgs(function, args)

  /** Effect shape detected on an annotated method's dealiased return type.
    *
    * Drives whether the macro-generated handler treats the method result as a pure value (wrap in
    * `ZIO.attempt`) or as an effect that must be flattened (`ZIO`, `Try`, `Either[Throwable, _]`).
    * Mirrors the typed-contract `ToHandlerEffect` typeclass.
    */
  private[macros] enum EffectShape:
    case Pure, Zio, TryEffect, EitherThrowable

  /** Classify the return type of a `@Tool` / `@Resource` / `@Prompt` method.
    *
    * Aborts at macro time if the method returns a `ZIO` with a non-`Any` environment, since the
    * shared handler signature `(args, ctx) => ZIO[Any, Throwable, Any]` cannot satisfy an
    * environment requirement.
    */
  private[macros] def detectEffectShape(using quotes: Quotes)(
      methodSym: quotes.reflect.Symbol
  ): EffectShape =
    import quotes.reflect.*

    val resType = (methodSym.info match
      case mt: MethodType => mt.resType
      case other => other
    ).dealias

    if resType <:< TypeRepr.of[zio.ZIO[Any, Any, Any]] then EffectShape.Zio
    else if resType <:< TypeRepr.of[zio.ZIO[Nothing, Any, Any]] then
      report.errorAndAbort(
        s"Annotated method '${methodSym.name}' returns ${resType.show}; " +
          "annotation-based handlers must return ZIO with environment Any. " +
          "Provide the environment via ZIO.provide(...) inside the method body, " +
          "or use a typed contract (McpTool.derived) for environment-dependent effects."
      )
    else if resType <:< TypeRepr.of[scala.util.Try[Any]] then EffectShape.TryEffect
    else if resType <:< TypeRepr.of[Either[Throwable, Any]] then EffectShape.EitherThrowable
    else EffectShape.Pure

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

    // Remove top-level $defs and $schema before starting resolution.
    // $schema (Tapir emits the draft-2020-12 meta-schema URL) violates Anthropic's
    // tool input_schema key pattern ^[a-zA-Z0-9_.-]{1,64}$ and breaks managed agents. See issue #44.
    val rootJsonWithoutDefs = inputJson.mapObject(_.remove("$defs").remove("$schema"))
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

  /** Recursively injects `@Param` metadata collected from typed request fields into an already
    * generated JSON schema.
    */
  def injectSchemaMetadata(schemaJson: Json, metadataNode: SchemaMetadataNode): Json = {
    import io.circe.parser.parse

    def applyOwnMetadata(currentJson: Json, metadata: Option[ParamMetadata]): Json =
      metadata match {
        case Some(meta) if meta.schema.isDefined =>
          parse(meta.schema.get).getOrElse(currentJson)
        case Some(meta) =>
          val baseObj = currentJson.asObject.getOrElse(JsonObject.empty)
          val withDesc =
            meta.description.fold(baseObj)(d => baseObj.add("description", Json.fromString(d)))
          val withExamples =
            if (meta.examples.nonEmpty) then
              withDesc.add("examples", Json.fromValues(meta.examples.map(Json.fromString)))
            else withDesc
          Json.fromJsonObject(withExamples)
        case None =>
          currentJson
      }

    val withOwnMetadata = applyOwnMetadata(schemaJson, metadataNode.metadata)

    // Custom schema replaces the field entirely.
    if metadataNode.metadata.exists(_.schema.isDefined) then withOwnMetadata
    else {
      val withItems =
        metadataNode.items match {
          case Some(itemNode) =>
            withOwnMetadata.hcursor.downField("items").focus match {
              case Some(itemsJson) =>
                withOwnMetadata.mapObject(
                  _.add("items", injectSchemaMetadata(itemsJson, itemNode))
                )
              case None =>
                withOwnMetadata
            }
          case None =>
            withOwnMetadata
        }

      withItems.hcursor.downField("properties").focus.flatMap(_.asObject) match {
        case Some(propsObj) if metadataNode.properties.nonEmpty =>
          val currentRequired =
            withItems.hcursor.downField("required").as[List[String]].getOrElse(Nil).toSet

          val updatedRequired = metadataNode.properties.foldLeft(currentRequired) {
            case (acc, (fieldName, childNode)) =>
              childNode.metadata match {
                case Some(meta) =>
                  if meta.required then acc + fieldName else acc - fieldName
                case None =>
                  acc
              }
          }

          val updatedProps = propsObj.toMap.map { case (fieldName, fieldJson) =>
            metadataNode.properties.get(fieldName) match {
              case Some(childNode) =>
                fieldName -> injectSchemaMetadata(fieldJson, childNode)
              case None =>
                fieldName -> fieldJson
            }
          }

          withItems.mapObject { obj =>
            val withProps =
              obj.add("properties", Json.fromJsonObject(JsonObject.fromMap(updatedProps)))
            if updatedRequired.nonEmpty then
              withProps.add(
                "required",
                Json.fromValues(updatedRequired.toSeq.sorted.map(Json.fromString))
              )
            else withProps.remove("required")
          }

        case _ =>
          withItems
      }
    }
  }
end MacroUtils
