package com.tjclp.fastmcp
package macros

import scala.quoted.*

import zio.json.*
import zio.json.ast.Json

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
    *
    * The trailing `taskSupport` slot carries the raw string value (`"forbidden"` | `"optional"` |
    * `"required"`) for the experimental Tasks feature; the caller validates it.
    */
  def parseToolAnnotationHints(using quotes: Quotes)(
      term: quotes.reflect.Term
  ): (
      Option[String],
      Option[Boolean],
      Option[Boolean],
      Option[Boolean],
      Option[Boolean],
      Option[Boolean],
      Option[String]
  ) =
    import quotes.reflect.*

    var title: Option[String] = None
    var readOnlyHint: Option[Boolean] = None
    var destructiveHint: Option[Boolean] = None
    var idempotentHint: Option[Boolean] = None
    var openWorldHint: Option[Boolean] = None
    var returnDirect: Option[Boolean] = None
    var taskSupport: Option[String] = None

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
          case NamedArg("taskSupport", valueTerm) =>
            taskSupport = parseOptionStringLiteral(valueTerm)
          case _ => ()
        }
      case _ => ()
    }
    (
      title,
      readOnlyHint,
      destructiveHint,
      idempotentHint,
      openWorldHint,
      returnDirect,
      taskSupport
    )

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
    * `ZIO` returns are accepted for any environment `R`. The annotation processors emit handlers
    * typed at `ContextualToolHandler[R]` so the surrounding server's `R` (set at construction time
    * via `McpServer.typed[R](...)`) is checked by Scala's normal type system against each method's
    * requirement. Providing the layer happens at `server.runHttp[R]().provide(...)`.
    */
  private[macros] def detectEffectShape(using quotes: Quotes)(
      methodSym: quotes.reflect.Symbol
  ): EffectShape =
    import quotes.reflect.*

    val resType = (methodSym.info match
      case mt: MethodType => mt.resType
      case other => other
    ).dealias

    val zioSym = TypeRepr.of[zio.ZIO].typeSymbol
    if resType.baseClasses.contains(zioSym) then EffectShape.Zio
    else if resType <:< TypeRepr.of[scala.util.Try[Any]] then EffectShape.TryEffect
    else if resType <:< TypeRepr.of[Either[Throwable, Any]] then EffectShape.EitherThrowable
    else EffectShape.Pure

  /** Extract the `R` type argument from a method that returns `ZIO[R, E, A]`. Returns `None` for
    * non-ZIO returns (the processor uses `Any` in that case — the handler doesn't require an
    * environment).
    */
  private[macros] def extractZioRequirement(using quotes: Quotes)(
      methodSym: quotes.reflect.Symbol
  ): Option[quotes.reflect.TypeRepr] =
    import quotes.reflect.*

    val resType = (methodSym.info match
      case mt: MethodType => mt.resType
      case other => other
    ).dealias

    val zioSym = TypeRepr.of[zio.ZIO].typeSymbol
    if resType.baseClasses.contains(zioSym) then
      resType.baseType(zioSym) match
        case AppliedType(_, args) if args.length == 3 => Some(args.head)
        case _ => None
    else None

  /** Takes a JSON schema potentially containing `$defs` and `$ref` and returns a new JSON schema
    * where all references are resolved and inlined. Retained for callers migrating hand-written
    * schemas; native derivation itself emits schemas without references.
    */
  def resolveJsonRefs(inputJson: Json): Json =
    val definitions = inputJson.asObject
      .flatMap(_.get("$defs"))
      .flatMap(_.asObject)
      .fold(Map.empty[String, Json])(_.toMap)

    def resolve(currentJson: Json): Json =
      currentJson match
        case obj: Json.Obj =>
          obj.get("$ref").flatMap(_.asString) match
            case Some(refPath) =>
              definitions.get(refPath.split('/').last).fold(currentJson)(resolve)
            case None =>
              Json.Obj(
                obj.fields
                  .filterNot(_._1 == "$defs")
                  .map { case (name, value) => name -> resolve(value) }
              )
        case array: Json.Arr => Json.Arr(array.elements.map(resolve))
        case scalar => scalar

    val rootWithoutDefinitions = inputJson.asObject
      .map(_.remove("$defs").remove("$schema"))
      .getOrElse(inputJson)
    resolve(rootWithoutDefinitions)

  /** Injects param descriptions (from @Param annotations) into the top-level "properties" fields of
    * the JSON schema. Returns a new Json with descriptions added.
    */
  def injectParamDescriptions(schemaJson: Json, descriptionMap: Map[String, String]): Json =
    val updatedProperties = for
      schemaObject <- schemaJson.asObject
      properties <- schemaObject.get("properties").flatMap(_.asObject)
    yield Json.Obj(
      properties.fields.map { case (fieldName, fieldJson) =>
        val updatedField = descriptionMap.get(fieldName) match
          case Some(description) =>
            fieldJson.asObject
              .map(replaceField(_, "description", Json.Str(description)))
              .getOrElse(fieldJson)
          case None => fieldJson
        fieldName -> updatedField
      }
    )

    (schemaJson.asObject, updatedProperties) match
      case (Some(schemaObject), Some(properties)) =>
        replaceField(schemaObject, "properties", properties)
      case _ => schemaJson

  /** Injects all @Param metadata into JSON schema properties.
    *   - description: Added to each property object
    *   - examples: Added as array to each property object (JSON Schema format)
    *   - required: Updates top-level "required" array
    *   - schema: Replaces entire property definition with custom schema
    */
  def injectParamMetadata(schemaJson: Json, metadataMap: Map[String, ParamMetadata]): Json =
    if metadataMap.isEmpty then schemaJson
    else
      val currentRequired = stringArrayField(schemaJson, "required").toSet
      val updatedRequired = metadataMap.foldLeft(currentRequired) { case (required, (name, meta)) =>
        if meta.required then required + name else required - name
      }

      schemaJson.asObject match
        case Some(schemaObject) =>
          val updatedProperties =
            schemaObject.get("properties").flatMap(_.asObject).map { properties =>
              Json.Obj(properties.fields.map { case (fieldName, fieldJson) =>
                val updatedField = metadataMap.get(fieldName) match
                  case Some(meta) => applyMetadata(fieldJson, meta)
                  case None => fieldJson
                fieldName -> updatedField
              })
            }
          withPropertiesAndRequired(schemaObject, updatedProperties, updatedRequired)
        case None => schemaJson

  /** Recursively injects `@Param` metadata collected from typed request fields into an already
    * generated JSON schema.
    */
  def injectSchemaMetadata(schemaJson: Json, metadataNode: SchemaMetadataNode): Json =
    val withOwnMetadata = metadataNode.metadata.fold(schemaJson)(applyMetadata(schemaJson, _))

    if metadataNode.metadata.exists(_.schema.isDefined) then withOwnMetadata
    else
      // Option fields render as {"anyOf":[inner, {"type":"null"}]}: the annotatable structure
      // (properties/items) lives inside the branches, so descend with the structural part of the
      // node (own metadata is already applied at the wrapper level above).
      val hasStructure = withOwnMetadata.asObject.exists(o =>
        o.get("properties").isDefined || o.get("items").isDefined
      )
      val anyOfBranches =
        if hasStructure then None
        else withOwnMetadata.asObject.flatMap(_.get("anyOf")).flatMap(_.asArray)
      anyOfBranches match
        case Some(branches) if metadataNode.properties.nonEmpty || metadataNode.items.isDefined =>
          val structuralNode = metadataNode.copy(metadata = None)
          val updated = branches.map(branch => injectSchemaMetadata(branch, structuralNode))
          replaceField(
            withOwnMetadata.asObject.getOrElse(Json.Obj()),
            "anyOf",
            Json.Arr(updated*)
          )
        case _ => injectStructuralMetadata(withOwnMetadata, metadataNode)

  private def injectStructuralMetadata(
      withOwnMetadata: Json,
      metadataNode: SchemaMetadataNode
  ): Json =
    val withItems = (metadataNode.items, withOwnMetadata.asObject) match
      case (Some(itemNode), Some(schemaObject)) =>
        schemaObject.get("items") match
          case Some(items) =>
            replaceField(schemaObject, "items", injectSchemaMetadata(items, itemNode))
          case None => withOwnMetadata
      case _ => withOwnMetadata

    withItems.asObject match
      case Some(schemaObject) if metadataNode.properties.nonEmpty =>
        schemaObject.get("properties").flatMap(_.asObject) match
          case Some(properties) =>
            val currentRequired = stringArrayField(withItems, "required").toSet
            val updatedRequired = metadataNode.properties.foldLeft(currentRequired) {
              case (required, (fieldName, childNode)) =>
                childNode.metadata match
                  case Some(meta) if meta.required => required + fieldName
                  case Some(_) => required - fieldName
                  case None => required
            }
            val updatedProperties = Json.Obj(properties.fields.map { case (fieldName, fieldJson) =>
              val updatedField = metadataNode.properties.get(fieldName) match
                case Some(childNode) => injectSchemaMetadata(fieldJson, childNode)
                case None => fieldJson
              fieldName -> updatedField
            })
            withPropertiesAndRequired(
              schemaObject,
              Some(updatedProperties),
              updatedRequired
            )
          case None => withItems
      case _ => withItems

  private def applyMetadata(schemaJson: Json, metadata: ParamMetadata): Json =
    metadata.schema.flatMap(_.fromJson[Json].toOption).getOrElse {
      val baseObject = schemaJson.asObject.getOrElse(Json.Obj())
      val withDescription = metadata.description.fold(baseObject) { description =>
        replaceField(baseObject, "description", Json.Str(description))
      }
      if metadata.examples.nonEmpty then
        replaceField(
          withDescription,
          "examples",
          Json.Arr(metadata.examples.map(Json.Str(_))*)
        )
      else withDescription
    }

  private def stringArrayField(json: Json, name: String): List[String] =
    json.asObject
      .flatMap(_.get(name))
      .flatMap(_.asArray)
      .fold(List.empty[String])(_.flatMap(_.asString).toList)

  private def withPropertiesAndRequired(
      schemaObject: Json.Obj,
      properties: Option[Json.Obj],
      required: Set[String]
  ): Json.Obj =
    val withProperties = properties.fold(schemaObject)(replaceField(schemaObject, "properties", _))
    if required.nonEmpty then
      replaceField(
        withProperties,
        "required",
        Json.Arr(required.toList.sorted.map(Json.Str(_))*)
      )
    else withProperties.remove("required")

  private def replaceField(obj: Json.Obj, name: String, value: Json): Json.Obj =
    if obj.contains(name) then
      Json.Obj(obj.fields.map {
        case (`name`, _) => name -> value
        case field => field
      })
    else Json.Obj(obj.fields :+ (name -> value))
end MacroUtils
