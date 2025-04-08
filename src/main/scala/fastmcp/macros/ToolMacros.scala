package fastmcp.macros

import fastmcp.server.FastMCPScala

import scala.quoted.*
import scala.reflect.ClassTag
import io.circe.{Json, Printer}
import io.circe.syntax.*
import sttp.apispec.circe.*

/**
 * Enhanced macro for scanning @Tool methods, building JSON schemas using Tapir, and registering with the server.
 *
 * Steps:
 *  1. Find methods with @Tool.
 *  2. Extract (name, description) from the annotation.
 *  3. Gather each parameter's name & type.
 *  4. For each parameter, derive a Schema using Tapir (or other library).
 *  5. Build a JSON schema: { type: "object", properties: {...}, required: [...] }
 *  6. Call server.registerMacroTool(...) with that info.
 */
object ToolMacros:

  /**
   * Entry point: called by something like:
   * inline def scanAnnotations[T]: Unit = ToolMacros.processAnnotations[T](server)
   */
  inline def processAnnotations[T](server: FastMCPScala): Unit =
    ${ processAnnotationsImpl[T]('server) }

  /**
   * The macro implementation that inspects type T, finds @Tool methods, and registers them.
   */
  private def processAnnotationsImpl[T: Type](serverExpr: Expr[FastMCPScala])(using quotes: Quotes): Expr[Unit] =
    import quotes.reflect.*

    // Explicitly summon ClassTag at macro expansion site
    Expr.summon[ClassTag[T]] match
      case Some(ctagExpr) =>
        // Now use ctagExpr as your ClassTag[T]
        val tSymbol = TypeRepr.of[T].typeSymbol
        if !tSymbol.isClassDef then
          return '{ () }

        val methods = tSymbol.declaredMethods

        val registrations = methods.flatMap { methodSym =>
          val maybeToolAnnot = methodSym.annotations.find(ann =>
            ann.tpe.derivesFrom(TypeRepr.of[fastmcp.core.Tool].typeSymbol)
          )

          maybeToolAnnot match
            case Some(annot) =>
              val (toolName, toolDesc) = parseToolAnnotation(quotes)(annot)
              val finalName = if toolName.nonEmpty then toolName else methodSym.name
              val finalDesc = if toolDesc.nonEmpty then Some(toolDesc) else None

              val (paramNames, paramTypes, requiredFlags, paramMeta) =
                extractMethodParameters(quotes)(methodSym)

              // Check if the method has the @InferSchema annotation
              val maybeInferSchemaAnnot = methodSym.annotations.find(ann =>
                ann.tpe.derivesFrom(TypeRepr.of[fastmcp.macros.InferSchema].typeSymbol)
              )

              // Generate schema based on annotation presence
              val schemaJson = if maybeInferSchemaAnnot.isDefined then
                generateSchemaFromMethod(quotes)(methodSym, paramNames)
              else
                buildJsonSchema(paramNames, paramTypes, requiredFlags, paramMeta)

              val nameExpr = Expr(finalName)
              val descExpr = finalDesc match
                case Some(desc) => '{ Some(${ Expr(desc) }) }
                case None => '{ None }
              val methodNameExpr = Expr(methodSym.name)
              val schemaJsonExpr = Expr(schemaJson)
              val paramNamesExpr = Expr(paramNames)
              val paramTypesExpr = Expr(paramTypes)
              val requiredFlagsExpr = Expr(requiredFlags)

              Some {
                '{
                  $serverExpr.registerMacroTool[T](
                    $nameExpr,
                    $descExpr,
                    $methodNameExpr,
                    $schemaJsonExpr,
                    $paramNamesExpr,
                    $paramTypesExpr,
                    $requiredFlagsExpr
                  )(using $ctagExpr)
                }
              }

            case None => None
        }

        if registrations.isEmpty then '{ () }
        else Expr.block(registrations.toList, '{ () })

      case None =>
        quotes.reflect.report.errorAndAbort(s"No ClassTag available for ${Type.show[T]}")

  /**
   * Generate JSON Schema for a method using JsonSchemaMacro
   * 
   * For methods marked with @InferSchema, this tries to generate a more accurate schema.
   * If schema generation fails, falls back to the simple schema builder.
   */
  private def generateSchemaFromMethod(q: Quotes)(methodSym: q.reflect.Symbol, paramNames: List[String]): String =
    import q.reflect.*
    
    java.lang.System.err.println(s"[ToolMacros] Using enhanced schema generation for method: ${methodSym.name}")
    
    try
      // For now, we'll use a simplified approach that focuses on parameter types
      // In a future version, this would use JsonSchemaMacro.schemaForFunctionArgs directly
      
      // Get parameter information 
      val paramLists = methodSym.paramSymss
      val allParams = paramLists.flatten
      
      // Build an enhanced schema based on parameter types
      val paramData = allParams.map { param =>
        val paramName = param.name
        val paramType = param.typeRef.show
        val isOption = paramType.startsWith("Option[")
        
        val description = s"Parameter: $paramName" // Could extract from @Param if present
        
        // Create a more accurate type description
        val jsonType = if paramType.contains("Int") || paramType.contains("Long") then 
          "integer"
        else if paramType.contains("Double") || paramType.contains("Float") then
          "number"
        else if paramType.contains("Boolean") then
          "boolean"
        else if paramType.contains("List") || paramType.contains("Array") || paramType.contains("Seq") then
          "array"
        else
          "string"
          
        // Build enhanced property
        val base = s"""  "$paramName": { "type": "$jsonType""""
        val withDesc = s"""$base, "description": "$description" """
        
        // Handle arrays
        val withItems = if jsonType == "array" then
          s"""$withDesc, "items": { "type": "string" } """
        else
          withDesc
        
        // Handle Option types
        val withNullable = if isOption then
          s"""$withItems, "nullable": true """
        else
          withItems
          
        // Close the property
        s"""$withNullable }"""
      }.mkString(",\n")
      
      // Build required fields (all non-Option params)
      val requiredParams = allParams
        .filter(p => !p.typeRef.show.startsWith("Option["))
        .map(p => s""""${p.name}"""")
        .mkString(", ")
      
      // Return the complete schema
      s"""{
         |  "type": "object",
         |  "properties": {
         |$paramData
         |  },
         |  "required": [ $requiredParams ],
         |  "additionalProperties": false
         |}""".stripMargin
    catch 
      case e: Exception =>
        java.lang.System.err.println(s"[ToolMacros] Error generating schema for ${methodSym.name}: ${e.getMessage}")
        e.printStackTrace(java.lang.System.err)
        // Fall back to old schema method
        val (paramTypes, requiredFlags, paramMeta) = 
          extractParameterInfo(q)(methodSym, paramNames)
        buildJsonSchema(paramNames, paramTypes, requiredFlags, paramMeta)
  
  /**
   * Helper to extract parameter info for the fallback method
   */
  private def extractParameterInfo(q: Quotes)(methodSym: q.reflect.Symbol, paramNames: List[String]): 
      (List[String], List[Boolean], List[Map[String, Any]]) =
    import q.reflect.*
    
    val paramLists = methodSym.paramSymss
    val allParams = paramLists.flatten
    
    val paramTypes = allParams.map(_.typeRef.show)
    val paramMeta = allParams.map(extractParamAnnotations(q))
    val requiredFlags = paramMeta.map(_.getOrElse("required", true).asInstanceOf[Boolean])
    
    (paramTypes, requiredFlags, paramMeta)

  // -- Helper: parse annotation arguments from @Tool(...)
  private def parseToolAnnotation(q: Quotes)(annotTerm: q.reflect.Term): (String, String) =
    import q.reflect.*
    // annotation is Tool(...) with named or unnamed params
    // We only care about the first two: name, description
    val args = annotTerm match
      case Apply(_, argList) => argList
      case _ => Nil

    val nameVal = args.lift(0).flatMap(extractOptionString(q)).getOrElse("")
    val descVal = args.lift(1).flatMap(extractOptionString(q)).getOrElse("")
    (nameVal, descVal)

  // Attempt to read `Option[String](...)`
  private def extractOptionString(q: Quotes)(term: q.reflect.Term): Option[String] =
    import q.reflect.*
    term match
      // Could be Some("value") or None
      case Typed(Apply(_, List(Literal(StringConstant(stringVal)))), _) => Some(stringVal)
      case Literal(StringConstant(stringVal)) => stringVal.nonEmptyOption
      case _ => None

  /**
   * Extract parameter annotations including @Param
   */
  private def extractParamAnnotations(q: Quotes)(paramSym: q.reflect.Symbol): Map[String, Any] =
    import q.reflect.*
    
    // Find @Param annotation if present
    val paramAnnotOpt = paramSym.annotations.find(ann => 
      ann.tpe.baseClasses.exists(_.fullName == "fastmcp.core.Param")
    )
    
    paramAnnotOpt match
      case Some(paramAnnot) =>
        val args = paramAnnot match
          case Apply(_, argList) => argList
          case _ => Nil
        
        // Extract annotation parameters: description, example, required, schema
        val description = args.lift(0) match
          case Some(Literal(StringConstant(desc))) => desc
          case _ => ""
          
        val example = args.lift(1).flatMap(extractOptionString(q))
        
        val required = args.lift(2) match
          case Some(Literal(BooleanConstant(req))) => req
          case _ => true  // Default to true if not specified
          
        val schema = args.lift(3).flatMap(extractOptionString(q))
        
        // Build metadata map
        val meta = Map[String, Any](
          "description" -> description
        )
        
        // Add optional fields if present
        val withExample = example.fold(meta)(e => meta + ("example" -> e))
        val withRequired = withExample + ("required" -> required)
        val withSchema = schema.fold(withRequired)(s => withRequired + ("schema" -> s))
        
        withSchema
        
      case None =>
        // No annotation - use defaults
        Map("description" -> "", "required" -> true)

  // -- Extract method param info: returns (List[paramNames], List[paramTypes], List[Boolean for required?], List[Map[String, Any]] for metadata)
  private def extractMethodParameters(q: Quotes)(methodSym: q.reflect.Symbol): (List[String], List[String], List[Boolean], List[Map[String, Any]]) =
    import q.reflect.*

    val paramLists = methodSym.paramSymss
    // We'll flatten them and ignore multiple param lists for now
    val allParams = paramLists.flatten

    // For each param, get name, type, required flag, and metadata
    val results = allParams.map { pSym =>
      val pName = pSym.name
      
      // Extract full type representation
      val pTypeRepr = pSym.typeRef
      val pType = pTypeRepr.show
      
      // Extract parameter annotations and metadata
      val paramMeta = extractParamAnnotations(q)(pSym)
      
      // Get required flag from metadata (default: true)
      val required = paramMeta.getOrElse("required", true).asInstanceOf[Boolean]
      
      (pName, pType, required, paramMeta)
    }
    
    // Get individual components
    val names = results.map(_._1)
    val types = results.map(_._2)
    val reqFlags = results.map(_._3)
    val meta = results.map(_._4)
    
    // Debug
    java.lang.System.err.println(s"[ToolMacros] Extracted parameter names: ${names.mkString(", ")}")
    java.lang.System.err.println(s"[ToolMacros] Extracted parameter types: ${types.mkString(", ")}")
    
    (names, types, reqFlags, meta)

  /**
   * Helper function to determine JSON Schema type from Scala type
   */
  private def getJsonSchemaType(scalaType: String): String =
    if scalaType.contains("Int") || scalaType.contains("Long") || scalaType.contains("Short") || scalaType.contains("Byte") then
      "integer"
    else if scalaType.contains("Double") || scalaType.contains("Float") || scalaType.contains("BigDecimal") then
      "number"
    else if scalaType.contains("Boolean") then
      "boolean"
    else if scalaType.contains("List") || scalaType.contains("Seq") || scalaType.contains("Array") || scalaType.contains("Set") then
      "array"
    else if scalaType.contains("Map") || scalaType.contains("Option") then
      "object"
    else
      "string"

  /**
   * Enhanced method to build JSON schema, handling nested types and using metadata from annotations
   */
  private def buildJsonSchema(
    paramNames: List[String],
    paramTypes: List[String],
    requiredFlags: List[Boolean],
    paramMeta: List[Map[String, Any]]
  ): String =
    // Create property entries with enhanced type detection and metadata
    val properties = paramNames.zip(paramTypes).zip(requiredFlags).zip(paramMeta).map {
      case (((pName, pType), required), meta) =>
        // Determine base JSON schema type
        val jType = getJsonSchemaType(pType)
        
        // Start with basic property schema
        val baseSchema = s"""  "$pName": { "type": "$jType""""
        
        // Add description if available
        val description = meta.getOrElse("description", "").toString
        val withDesc = if description.nonEmpty then
          s"""$baseSchema, "description": "${description.replace("\"", "\\\"")}" """
        else
          baseSchema
        
        // Handle array types
        val withItems = if jType == "array" then
          s"""$withDesc, "items": { "type": "string" } """
        else
          withDesc
        
        // Handle Option types
        val withNullable = if pType.contains("Option") then
          s"""$withItems, "nullable": true """
        else
          withItems
        
        // Add example if available
        val withExample = meta.get("example") match {
          case Some(example) => 
            s"""$withNullable, "example": "${example.toString.replace("\"", "\\\"")}" """
          case None => 
            withNullable
        }
        
        // Close property schema
        s"""$withExample }"""
    }.mkString(",\n")

    // Build required fields list
    val requiredList = paramNames.zip(requiredFlags)
      .collect { case (n, true) => s""""$n"""" }
      .mkString(",")

    // Build final schema
    s"""{
       |  "type": "object",
       |  "properties": {
       |$properties
       |  },
       |  "required": [ $requiredList ]
       |}""".stripMargin

  /**
   * Convert property map to JSON.
   */
  private def propertyMapToJson(schema: Map[String, Any]): Json =
    Json.fromFields(schema.map { case (k, v) => k -> propertyToJson(v) })

  /**
   * Convert a property definition to a JSON value.
   */
  private def propertyToJson(value: Any): Json =
    value match {
      case s: String => Json.fromString(s)
      case n: Int => Json.fromInt(n)
      case n: Long => Json.fromLong(n)
      case n: Double => Json.fromDoubleOrString(n)
      case b: Boolean => Json.fromBoolean(b)
      case null => Json.Null
      case m: Map[?, ?] => 
        val stringMap = m.asInstanceOf[Map[String, Any]]
        Json.fromFields(stringMap.map { case (k, v) => k -> propertyToJson(v) })
      case s: Seq[?] => Json.fromValues(s.map(propertyToJson))
      case _ => Json.fromString(value.toString)
    }


extension (s: String)
  def nonEmptyOption: Option[String] =
    if s.trim.isEmpty then None else Some(s)